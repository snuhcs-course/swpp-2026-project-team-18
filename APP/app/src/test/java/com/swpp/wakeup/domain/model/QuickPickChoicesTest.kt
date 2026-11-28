package com.swpp.wakeup.domain.model

import org.junit.Assert.*
import org.junit.Test

/** Iteration 5 P14/P15: no fabricated choices or silent clock-to-risk remapping. */
class QuickPickChoicesTest {
    private fun option(key: String, time: String, tau: Double, period: String = "AM") =
        RiskOptionView(key, key, time, period, 83, null, tau)

    private fun options(
        vararg rows: RiskOptionView,
        recommended: String? = null,
        selected: String? = null,
    ) = RiskOptionsView(rows.toList(), selected, recommended, false, null, null)

    @Test fun `distinct server choices preserve exact values and order`() {
        val input = options(option("safe", "7:10", .97), option("normal", "7:20", .9), option("gamble", "7:30", .65))
        val result = AlarmTimeChoice.quickPicks(input)
        assertEquals(input.options, result.options)
        assertNull(result.recommendedKey)
        assertFalse(result.collapsed)
    }

    @Test fun `recommendation wins identical clocks without inventing a normal fallback`() {
        val input = options(
            option("safe", "7:10", .97), option("normal", "7:10", .9), option("gamble", "7:10", .65),
            recommended = "normal", selected = "gamble",
        )
        val result = AlarmTimeChoice.quickPicks(input)
        assertEquals(listOf(input.options[1]), result.options)
        assertEquals("normal", result.recommendedKey)
        assertTrue(result.collapsed)
        assertNull(AlarmTimeChoice.quickPicks(input.copy(recommendedKey = null)).recommendedKey)
    }

    @Test fun `current choice wins collision when recommendation is unknown`() {
        val input = options(option("safe", "7:10", .97), option("gamble", "7:12", .65), selected = "gamble")
        assertEquals(listOf(input.options[1]), AlarmTimeChoice.quickPicks(input).options)
    }

    @Test fun `small differences collapse but five minute differences remain`() {
        val input = options(
            option("safe", "7:16", .97), option("normal", "7:20", .9), option("gamble", "7:25", .65),
            recommended = "normal",
        )
        val result = AlarmTimeChoice.quickPicks(input)
        assertEquals(listOf("normal", "gamble"), result.options.map { it.key })
        assertTrue(result.collapsed)
    }

    @Test fun `midnight neighbors collapse using circular rather than clock order`() {
        val input = options(
            option("safe", "11:59", .97, "PM"), option("normal", "12:01", .9), option("gamble", "12:06", .65),
            recommended = "normal",
        )
        assertEquals(listOf("normal", "gamble"), AlarmTimeChoice.quickPicks(input).options.map { it.key })
    }

    @Test fun `invalid choices are unavailable without changing valid probabilities`() {
        val valid = option("safe", "7:15", .97).copy(probability = null)
        val input = options(valid, option("bad-clock", "99:99", .9), option("bad-tau", "7:40", Double.NaN))
        val result = AlarmTimeChoice.quickPicks(input)
        assertEquals(listOf(valid), result.options)
        assertNull(result.options.single().probability)
        assertTrue(result.collapsed)
        assertTrue(AlarmTimeChoice.quickPicks(null).options.isEmpty())
    }

    @Test fun `an explicitly selected equal-clock card keeps its exact key`() {
        val clocks = linkedMapOf("safe" to 430, "normal" to 430, "gamble" to 430)
        assertEquals(UserAlarmRule.Save.Preset("gamble"), UserAlarmRule.choose(
            430, clocks, supported = true, preferredPresetKey = "gamble",
        ))
        assertEquals(UserAlarmRule.Save.Preset("safe"), UserAlarmRule.choose(
            430, clocks, supported = true, preferredPresetKey = "missing",
        ))
    }

    @Test fun `an old card key cannot override a subsequently changed wheel time`() {
        assertEquals(UserAlarmRule.Save.Custom(440), UserAlarmRule.choose(
            440, mapOf("gamble" to 430), supported = true, preferredPresetKey = "gamble",
        ))
    }
}
