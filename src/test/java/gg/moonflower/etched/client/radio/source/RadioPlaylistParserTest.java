package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RadioPlaylistParserTest {

    private static final URI BASE = URI.create("https://radio.example/lists/stations.m3u?old=true");
    private static final AudioResolveLimits LIMITS = new AudioResolveLimits(16, 1024, 10, 256, 2, 20);

    @Test
    void parsesPlainAndExtendedM3uEntriesRelativeToTheFinalUri() throws Exception {
        byte[] playlist = ("\uFEFF#EXTM3U\r\n"
                + "# an unknown comment\r\n"
                + "#EXTINF:-1,Primary station\r\n"
                + "../primary.mp3\r\n"
                + "?quality=low\r\n"
                + "https://backup.example/live\r\n").getBytes(StandardCharsets.UTF_8);

        List<RadioPlaylistEntry> entries = M3uRadioPlaylistParser.parse(BASE, playlist, LIMITS);

        assertEquals(List.of(
                URI.create("https://radio.example/primary.mp3"),
                URI.create("https://radio.example/lists/stations.m3u?quality=low"),
                URI.create("https://backup.example/live")),
                entries.stream().map(RadioPlaylistEntry::uri).toList());
        assertEquals("Primary station", entries.get(0).title());
        assertNull(entries.get(1).title());
    }

    @Test
    void preservesRawEncodingWhenReplacingQueriesAndRemovingFragments() throws Exception {
        URI encodedBase = URI.create("https://radio.example/a%20b/stations.m3u?old=x%20y");

        List<RadioPlaylistEntry> entries = M3uRadioPlaylistParser.parse(encodedBase,
                bytes("?token=a%20b#ignored\n../c%23d.mp3#ignored\n"), LIMITS);

        assertEquals(List.of(
                URI.create("https://radio.example/a%20b/stations.m3u?token=a%20b"),
                URI.create("https://radio.example/c%23d.mp3")),
                entries.stream().map(RadioPlaylistEntry::uri).toList());
    }

    @Test
    void rejectsHlsAndForbiddenM3uEntries() {
        RadioSourceException hls = assertThrows(RadioSourceException.class,
                () -> M3uRadioPlaylistParser.parse(BASE,
                        bytes("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nstream.m3u8\n"), LIMITS));
        RadioSourceException scheme = assertThrows(RadioSourceException.class,
                () -> M3uRadioPlaylistParser.parse(BASE, bytes("file:///etc/passwd\n"), LIMITS));

        assertEquals(RadioFailure.Code.UNSUPPORTED_HLS, hls.code());
        assertEquals(RadioFailure.Code.UNSUPPORTED_AUDIO, scheme.code());
    }

    @Test
    void enforcesM3uEntryAndLineLimits() {
        AudioResolveLimits oneEntry = new AudioResolveLimits(4, 128, 1, 32, 1, 4);
        RadioSourceException entries = assertThrows(RadioSourceException.class,
                () -> M3uRadioPlaylistParser.parse(BASE, bytes("one.mp3\ntwo.mp3\n"), oneEntry));
        RadioSourceException line = assertThrows(RadioSourceException.class,
                () -> M3uRadioPlaylistParser.parse(BASE, bytes("123456789.mp3\n"),
                        new AudioResolveLimits(4, 128, 2, 8, 1, 4)));

        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, entries.code());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, line.code());
    }

    @Test
    void parsesPlsFilesInNumericOrderRegardlessOfPropertyOrder() throws Exception {
        byte[] playlist = bytes("""
                [playlist]
                Title10=Tenth
                File10=../ten.mp3
                File2=https://backup.example/two.mp3
                Title1=First
                NumberOfEntries=99
                File1=one.mp3
                Version=2
                """);

        List<RadioPlaylistEntry> entries = PlsRadioPlaylistParser.parse(BASE, playlist, LIMITS);

        assertEquals(List.of(
                URI.create("https://radio.example/lists/one.mp3"),
                URI.create("https://backup.example/two.mp3"),
                URI.create("https://radio.example/ten.mp3")),
                entries.stream().map(RadioPlaylistEntry::uri).toList());
        assertEquals("First", entries.get(0).title());
        assertEquals("Tenth", entries.get(2).title());
    }

    @Test
    void rejectsMalformedOrEmptyPls() {
        RadioSourceException missingHeader = assertThrows(RadioSourceException.class,
                () -> PlsRadioPlaylistParser.parse(BASE, bytes("File1=stream.mp3\n"), LIMITS));
        RadioSourceException badIndex = assertThrows(RadioSourceException.class,
                () -> PlsRadioPlaylistParser.parse(BASE, bytes("[playlist]\nFileX=stream.mp3\n"), LIMITS));
        RadioSourceException empty = assertThrows(RadioSourceException.class,
                () -> PlsRadioPlaylistParser.parse(BASE, bytes("[playlist]\nVersion=2\n"), LIMITS));

        assertEquals(RadioFailure.Code.UNSUPPORTED_AUDIO, missingHeader.code());
        assertEquals(RadioFailure.Code.UNSUPPORTED_AUDIO, badIndex.code());
        assertEquals(RadioFailure.Code.UNSUPPORTED_AUDIO, empty.code());
    }

    @Test
    void enforcesPlsEntryAndLineBoundaries() throws Exception {
        AudioResolveLimits exact = new AudioResolveLimits(4, 128, 1, 10, 1, 4);
        List<RadioPlaylistEntry> one = PlsRadioPlaylistParser.parse(
                BASE, bytes("[playlist]\nFile1=x\n"), exact);
        RadioSourceException entries = assertThrows(RadioSourceException.class,
                () -> PlsRadioPlaylistParser.parse(
                        BASE, bytes("[playlist]\nFile1=x\nFile2=y\n"), exact));
        RadioSourceException line = assertThrows(RadioSourceException.class,
                () -> PlsRadioPlaylistParser.parse(BASE, bytes("[playlist]\nFile1=x\n"),
                        new AudioResolveLimits(4, 128, 1, 9, 1, 4)));

        assertEquals(1, one.size());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, entries.code());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, line.code());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
