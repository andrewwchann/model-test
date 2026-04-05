package com.andre.alprprototype

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {
    @Test
    fun nanosToMillis_truncates_to_whole_milliseconds() {
        assertEquals(0L, nanosToMillis(999_999L))
        assertEquals(1L, nanosToMillis(1_000_000L))
        assertEquals(12L, nanosToMillis(12_345_678L))
    }
}
