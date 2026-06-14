package xyz.aprildown.timer.app.base.data

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.aprildown.timer.app.base.data.PreferenceData.normalizeRecentTemporaryDurations

class PreferenceDataTest {

    @Test
    fun `recent temporary durations are newest-first unique and capped`() {
        assertEquals(
            listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L, 2_000L),
            normalizeRecentTemporaryDurations(
                listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L, 2_000L, 1_000L)
            )
        )
    }

    @Test
    fun `recent temporary durations ignore non-positive values and duplicates`() {
        assertEquals(
            listOf(3_000L, 2_000L, 1_000L),
            normalizeRecentTemporaryDurations(
                listOf(0L, -1L, 3_000L, 2_000L, 3_000L, 1_000L, 2_000L)
            )
        )
    }
}
