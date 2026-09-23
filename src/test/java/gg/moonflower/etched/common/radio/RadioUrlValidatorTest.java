package gg.moonflower.etched.common.radio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioUrlValidatorTest {

    @Test
    void acceptsAndTrimsHttpSourcesWithoutRewritingPathOrQuery() {
        String source = "https://Radio.Example/a%2Fb?signature=A%2BB#track";
        RadioUrlValidator.Result result = RadioUrlValidator.validate("  " + source + "  ");

        assertTrue(result.valid());
        assertEquals(source, result.normalized());
    }

    @Test
    void acceptsIntentionalEmptyStation() {
        RadioUrlValidator.Result result = RadioUrlValidator.validate(" \t ");

        assertTrue(result.valid());
        assertEquals("", result.normalized());
    }

    @Test
    void acceptsCaseInsensitiveHttpSchemes() {
        assertTrue(RadioUrlValidator.validate("HTTP://radio.example/live").valid());
        assertTrue(RadioUrlValidator.validate("Https://radio.example/live").valid());
    }

    @Test
    void rejectsMalformedAndUnsupportedSources() {
        assertFalse(RadioUrlValidator.validate(null).valid());
        assertFalse(RadioUrlValidator.validate("radio.example/live").valid());
        assertFalse(RadioUrlValidator.validate("ftp://radio.example/live").valid());
        assertFalse(RadioUrlValidator.validate("https:///live").valid());
        assertFalse(RadioUrlValidator.validate("https://radio.example/a b").valid());
        assertFalse(RadioUrlValidator.validate("https://user:secret@radio.example/live").valid());
        assertFalse(RadioUrlValidator.validate("https://radio.example/\nnext").valid());
        assertFalse(RadioUrlValidator.validate("https://radio.example:0/live").valid());
        assertFalse(RadioUrlValidator.validate("https://radio.example:65536/live").valid());
    }

    @Test
    void rejectsValuesBeyondTheProtocolLimit() {
        String oversized = "https://radio.example/" + "a".repeat(RadioUrlValidator.MAX_LENGTH);

        assertFalse(RadioUrlValidator.validate(oversized).valid());
    }
}
