package gg.moonflower.etched.client.radio.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Bounded demuxer for one clear AAC-LC track in ISO BMFF fragments. No network or codec work. */
final class FragmentedMp4Aac {
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350};
    private final int trackId, frequencyIndex, channels, defaultSize, defaultDuration;
    private long nextTime = -1;
    private long decodedSamples;

    private FragmentedMp4Aac(int trackId, int frequencyIndex, int channels, int size, int duration) {
        this.trackId = trackId;
        this.frequencyIndex = frequencyIndex;
        this.channels = channels;
        this.defaultSize = size;
        this.defaultDuration = duration;
    }

    static FragmentedMp4Aac initialization(byte[] bytes) throws IOException {
        try {
            Box moov = one(boxes(bytes, 0, bytes.length), "moov");
            Box trak = one(moov.children(), "trak");
            ByteBuffer tkhd = one(trak.children(), "tkhd").data();
            int version = tkhd.getInt() >>> 24;
            require(version <= 1, "Unsupported track header version");
            tkhd.position(version == 1 ? 20 : 12);
            int id = tkhd.getInt();
            require(id > 0, "Invalid track ID");
            Box mdia = one(trak.children(), "mdia");
            ByteBuffer hdlr = one(mdia.children(), "hdlr").data();
            hdlr.position(8);
            require(hdlr.getInt() == type("soun"), "Only audio tracks are supported");
            ByteBuffer mdhd = one(mdia.children(), "mdhd").data();
            int mdVersion = mdhd.getInt() >>> 24;
            require(mdVersion <= 1, "Unsupported media header version");
            mdhd.position(mdVersion == 1 ? 20 : 12);
            int timescale = mdhd.getInt();
            Box stbl = one(one(mdia.children(), "minf").children(), "stbl");
            Box stsd = one(stbl.children(), "stsd");
            ByteBuffer descriptions = stsd.data();
            require(descriptions.getInt() == 0 && descriptions.getInt() == 1, "Expected one sample description");
            Box sample = one(boxes(bytes, stsd.payload + 8, stsd.end), "mp4a");
            ByteBuffer audio = sample.data();
            require(audio.remaining() >= 28, "Truncated audio sample entry");
            require(audio.getShort(8) == 0, "Unsupported audio sample entry version");
            int sampleChannels = Short.toUnsignedInt(audio.getShort(16));
            int sampleRate = audio.getInt(24) >>> 16;
            List<Box> extensions = boxes(bytes, sample.payload + 28, sample.end);
            require(extensions.stream().noneMatch(b -> b.type == type("sinf")), "Encrypted MP4 is unsupported");
            ByteBuffer esds = one(extensions, "esds").data();
            require(esds.getInt() == 0, "Unsupported ES descriptor version");
            ByteBuffer es = descriptor(esds, 3);
            es.getShort();
            int flags = Byte.toUnsignedInt(es.get());
            require(flags == 0, "Unsupported ES descriptor flags");
            ByteBuffer config = descriptor(es, 4);
            require(Byte.toUnsignedInt(config.get()) == 0x40, "Expected MPEG-4 audio");
            require((Byte.toUnsignedInt(config.get()) >>> 2) == 5, "Expected audio stream type");
            config.position(13);
            ByteBuffer asc = descriptor(config, 5);
            require(asc.remaining() >= 2 && asc.remaining() <= 16, "Invalid AudioSpecificConfig");
            int bits = Short.toUnsignedInt(asc.getShort());
            int profile = bits >>> 11;
            int frequency = (bits >>> 7) & 15;
            int channels = (bits >>> 3) & 15;
            require(profile == 2 && frequency < RATES.length && (channels == 1 || channels == 2)
                    && (bits & 7) == 0, "Only standard mono/stereo AAC-LC is supported");
            // FFmpeg writes a sync extension explicitly declaring SBR absent (0x2b7, AOT 5, false).
            if (asc.remaining() == 3 && (asc.get(asc.position()) & 255) == 0x56
                    && (asc.get(asc.position() + 1) & 255) == 0xe5
                    && asc.get(asc.position() + 2) == 0) asc.position(asc.limit());
            while (asc.hasRemaining()) require(asc.get() == 0, "AAC extensions are unsupported");
            require(timescale == RATES[frequency] && sampleRate == timescale && sampleChannels == channels,
                    "Conflicting MP4 audio configuration");
            ByteBuffer trex = one(one(moov.children(), "mvex").children(), "trex").data();
            require(trex.getInt() == 0 && trex.getInt() == id && trex.getInt() == 1, "Invalid track defaults");
            int duration = trex.getInt(), size = trex.getInt();
            return new FragmentedMp4Aac(id, frequency, channels, size, duration);
        } catch (RuntimeException e) {
            throw new IOException("Malformed MP4 initialization", e);
        }
    }

    byte[] fragment(byte[] bytes) throws IOException {
        try {
            List<Box> top = boxes(bytes, 0, bytes.length);
            Box moof = one(top, "moof"), mdat = one(top, "mdat");
            Box traf = one(moof.children(), "traf");
            List<Box> children = traf.children();
            require(children.stream().noneMatch(b -> b.type == type("senc") || b.type == type("saiz")
                    || b.type == type("saio")), "Encrypted fragments are unsupported");
            ByteBuffer tfhd = one(children, "tfhd").data();
            int flags = tfhd.getInt();
            require((flags & ~0x02003a) == 0 && (flags & 0x020000) != 0,
                    "Expected fragment-relative MP4 offsets");
            require(tfhd.getInt() == trackId, "Fragment belongs to another track");
            if ((flags & 2) != 0) require(tfhd.getInt() == 1, "Sample description changed");
            int duration = (flags & 8) != 0 ? tfhd.getInt() : defaultDuration;
            int size = (flags & 16) != 0 ? tfhd.getInt() : defaultSize;
            if ((flags & 32) != 0) tfhd.getInt();
            require(!tfhd.hasRemaining(), "Unexpected track header data");
            ByteBuffer tfdt = one(children, "tfdt").data();
            int version = tfdt.getInt();
            require(version == 0 || version == 0x01000000, "Unsupported decode time version");
            long time = version == 0 ? Integer.toUnsignedLong(tfdt.getInt()) : tfdt.getLong();
            require(time >= 0 && (nextTime < 0 || time == nextTime), "Discontinuous MP4 timeline");
            ByteBuffer run = one(children, "trun").data();
            int runFlags = run.getInt();
            require((runFlags & ~0x01000f05) == 0 && (runFlags & 1) != 0, "Unsupported fragment run");
            int count = run.getInt();
            require(count > 0 && count <= 16384 && count * 1024L <= RATES[frequencyIndex] * 61L,
                    "Invalid or oversized fragment sample count");
            require(decodedSamples + count * 1024L <= RATES[frequencyIndex] * 6L * 3600,
                    "AAC track exceeds the decoded duration limit");
            long offset = (long) moof.start + run.getInt();
            if ((runFlags & 4) != 0) run.getInt();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(bytes.length, 64 * 1024));
            for (int i = 0; i < count; i++) {
                int sampleDuration = (runFlags & 0x100) != 0 ? run.getInt() : duration;
                int sampleSize = (runFlags & 0x200) != 0 ? run.getInt() : size;
                if ((runFlags & 0x400) != 0) run.getInt();
                if ((runFlags & 0x800) != 0) require(run.getInt() == 0, "AAC composition offset is unsupported");
                require(sampleDuration > 0 && sampleDuration <= 1024, "Invalid AAC sample duration");
                require(sampleSize > 0 && sampleSize <= 8184 && offset >= mdat.payload
                        && offset + sampleSize <= mdat.end, "AAC sample exceeds media bounds");
                int length = sampleSize + 7;
                output.write(0xff); output.write(0xf1);
                output.write(0x40 | (frequencyIndex << 2) | (channels >>> 2));
                output.write((channels << 6) | (length >>> 11));
                output.write(length >>> 3); output.write((length << 5) | 0x1f); output.write(0xfc);
                output.write(bytes, (int) offset, sampleSize);
                offset += sampleSize;
                time = Math.addExact(time, sampleDuration);
            }
            require(!run.hasRemaining(), "Unexpected fragment sample data");
            nextTime = time;
            decodedSamples += count * 1024L;
            return output.toByteArray();
        } catch (RuntimeException e) {
            throw new IOException("Malformed MP4 fragment", e);
        }
    }

    private static ByteBuffer descriptor(ByteBuffer parent, int expected) throws IOException {
        require(Byte.toUnsignedInt(parent.get()) == expected, "Unexpected MPEG-4 descriptor");
        int length = 0, value = 0;
        for (int i = 0; i < 4; i++) {
            value = Byte.toUnsignedInt(parent.get());
            length = (length << 7) | (value & 127);
            if ((value & 128) == 0) break;
        }
        require((value & 128) == 0 && length <= parent.remaining(), "Invalid MPEG-4 descriptor length");
        ByteBuffer data = parent.slice().order(ByteOrder.BIG_ENDIAN);
        data.limit(length);
        parent.position(parent.position() + length);
        return data;
    }

    private static List<Box> boxes(byte[] bytes, int start, int end) throws IOException {
        List<Box> result = new ArrayList<>();
        for (int pos = start; pos < end;) {
            require(result.size() < 1024 && end - pos >= 8, "Invalid MP4 box table");
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            long size = Integer.toUnsignedLong(input.getInt(pos));
            int type = input.getInt(pos + 4), header = 8;
            if (size == 1) {
                require(end - pos >= 16, "Truncated large MP4 box");
                size = input.getLong(pos + 8); header = 16;
            }
            require(size >= header && size <= end - pos, "MP4 box exceeds bounds");
            result.add(new Box(bytes, type, pos, pos + header, pos + (int) size));
            pos += (int) size;
        }
        return result;
    }

    private static Box one(List<Box> boxes, String name) throws IOException {
        List<Box> matches = boxes.stream().filter(b -> b.type == type(name)).toList();
        require(matches.size() == 1, "Expected one MP4 " + name + " box");
        return matches.get(0);
    }

    private static int type(String text) {
        return text.charAt(0) << 24 | text.charAt(1) << 16 | text.charAt(2) << 8 | text.charAt(3);
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }

    private record Box(byte[] bytes, int type, int start, int payload, int end) {
        ByteBuffer data() { return ByteBuffer.wrap(bytes, payload, end - payload).slice().order(ByteOrder.BIG_ENDIAN); }
        List<Box> children() throws IOException { return boxes(bytes, payload, end); }
    }
}
