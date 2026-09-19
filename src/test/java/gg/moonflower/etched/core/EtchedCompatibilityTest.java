package gg.moonflower.etched.core;

import net.minecraftforge.fml.IExtensionPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchedCompatibilityTest {

    @Test
    void advertisesAndAcceptsOriginalEtched304() {
        IExtensionPoint.DisplayTest displayTest = EtchedCompatibility.displayTest();

        assertEquals("3.0.4", displayTest.suppliedVersion().get());
        assertTrue(displayTest.remoteVersionTest().test("3.0.4", true));
        assertTrue(displayTest.remoteVersionTest().test("3.0.4", false));
        assertTrue(displayTest.remoteVersionTest().test("4.0.0", true));
        assertTrue(displayTest.remoteVersionTest().test("4.0.0", false));
        assertTrue(displayTest.remoteVersionTest().test("4.0.0-beta.1", true));
        assertTrue(displayTest.remoteVersionTest().test("4.0.0-beta.1", false));
        assertTrue(displayTest.remoteVersionTest().test("4.1.0", true));
        assertTrue(displayTest.remoteVersionTest().test("4.1.0", false));
    }

    @Test
    void rejectsUnverifiedOriginalVersions() {
        IExtensionPoint.DisplayTest displayTest = EtchedCompatibility.displayTest();

        assertFalse(displayTest.remoteVersionTest().test("3.0.3", true));
        assertFalse(displayTest.remoteVersionTest().test("3.0.5", true));
        assertFalse(displayTest.remoteVersionTest().test("5.0.0", true));
        assertFalse(displayTest.remoteVersionTest().test(null, true));
    }
}
