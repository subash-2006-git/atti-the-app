package com.subash.atti

import com.subash.atti.data.NameRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameRulesTest {
    @Test fun `blank names are invalid`() {
        assertFalse(NameRules.isValid(""))
        assertFalse(NameRules.isValid("    "))
    }

    @Test fun `names of 1 to 20 characters are valid`() {
        assertTrue(NameRules.isValid("S"))
        assertTrue(NameRules.isValid("a".repeat(20)))
        assertFalse(NameRules.isValid("a".repeat(21)))
    }

    @Test fun `surrounding spaces do not count toward the limit`() {
        assertTrue(NameRules.isValid("  " + "a".repeat(20) + "  "))
    }

    @Test fun `clean trims and collapses whitespace`() {
        assertEquals("Sub ash", NameRules.clean("  Sub   ash "))
    }
}
