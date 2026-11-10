package com.zcc09.hermespocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionToolsTest {

    @Test
    fun twoDigitComponentIsNewerThanSingleDigit() {
        assertTrue(VersionTools.isNewer("1.9.0", "1.10.0"))
        assertFalse(VersionTools.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun equalVersionsAreNotNewer() {
        assertFalse(VersionTools.isNewer("1.10.0", "1.10.0"))
        assertFalse(VersionTools.isNewer("2.0.1", "2.0.1"))
    }

    @Test
    fun higherLeadingComponentIsNewer() {
        assertTrue(VersionTools.isNewer("0.9.0", "1.0.0"))
        assertFalse(VersionTools.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun shorterStringPadsWithZero() {
        assertFalse(VersionTools.isNewer("1.2", "1.2.0"))
        assertTrue(VersionTools.isNewer("1.2", "1.2.1"))
    }

    @Test
    fun unparseableInputReturnsFalse() {
        assertFalse(VersionTools.isNewer("", "1.0.0"))
        assertFalse(VersionTools.isNewer("1.0.0", ""))
        assertFalse(VersionTools.isNewer("abc", "1.0.0"))
        assertFalse(VersionTools.isNewer("1.0.0", "x.y.z"))
        assertFalse(VersionTools.isNewer("1.0.x", "1.0.0"))
    }

    @Test
    fun leadingVIsIgnored() {
        assertTrue(VersionTools.isNewer("1.0.0", "v1.1.0"))
        assertTrue(VersionTools.isNewer("v1.0.0", "1.0.1"))
        assertFalse(VersionTools.isNewer("v1.1.0", "v1.1.0"))
    }
}
