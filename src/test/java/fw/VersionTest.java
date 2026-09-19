package fw;

import fw.model.Version;
import fw.model.VersionRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionTest {

    @Test
    void comparesDottedVersionsNumerically() {
        assertTrue(Version.of("2.1.0").isGreaterThan(Version.of("2.0.9")));
        assertTrue(Version.of("10").isGreaterThan(Version.of("9.1")));
        assertEquals(Version.of("1.2"), Version.of("1.2.0"));
        assertTrue(Version.of("1.2").isLessThan(Version.of("1.2.1")));
    }

    @Test
    void rejectsMalformedVersions() {
        assertFalse(Version.isValid("1..2"));
        assertFalse(Version.isValid("1.x"));
        assertThrows(IllegalArgumentException.class, () -> Version.of(""));
    }

    @Test
    void rangeFormsWork() {
        VersionRange range = VersionRange.parse("1.0.0..2.3.0");
        assertTrue(range.contains("1.0.0"));
        assertTrue(range.contains("2.3.0"));
        assertFalse(range.contains("2.3.1"));
        assertEquals("1.4.0", VersionRange.parse("1.4.0").min());
        assertTrue(VersionRange.parse("*").contains("99.99"));
        assertTrue(VersionRange.parse(">=1.0").contains("5.0"));
        assertTrue(VersionRange.parse("<=3.0").contains("3.0"));
    }
}
