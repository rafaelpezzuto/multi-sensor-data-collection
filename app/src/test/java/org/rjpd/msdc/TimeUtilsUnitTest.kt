package org.rjpd.msdc

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsUnitTest {
    @Test
    fun getDateTimeUTCSensor_returnsCorrectDateTime() {
        val systemCurrentTimeMillis = 1678886400000
        val timestampNano = 1000000000L
        val elapsedRealtimeNano = 5000000000
        val expectedDateTime = DateTime(1678886400000 + 1000 - 5000, DateTimeZone.UTC)

        val dateTime = TimeUtils.getDateTimeUTCSensor(systemCurrentTimeMillis, timestampNano, elapsedRealtimeNano)

        assertEquals(expectedDateTime, dateTime)
    }
}