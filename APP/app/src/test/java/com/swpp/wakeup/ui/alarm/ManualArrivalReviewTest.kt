package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.sensing.LocationFix
import org.junit.Assert.*
import org.junit.Test

class ManualArrivalReviewTest {
    private val now = 1_800_000_000_000L
    private val destination = GeoPoint(37.46, 126.95)
    private fun fix(lat: Double = destination.lat, accuracy: Float = 10f, at: Long = now) =
        LocationFix(lat, destination.lng, accuracy, at)

    @Test fun `fresh accurate near position can be described as near without auto recording`() {
        assertEquals(ManualArrivalReview.Position.NEAR, ManualArrivalReview.position(fix(), destination, now))
    }

    @Test fun `known far position requires even the accuracy lower bound beyond radius`() {
        assertEquals(ManualArrivalReview.Position.FAR, ManualArrivalReview.position(fix(lat = 37.47), destination, now))
        // Around 100m away with 50m error overlaps the boundary, so do not claim far.
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(lat = 37.4609, accuracy = 50f), destination, now))
    }

    @Test fun `unknown destination or missing inaccurate coordinates stays unknown`() {
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(null, destination, now))
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(), null, now))
        for (accuracy in listOf(51f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(accuracy = accuracy), destination, now))
        }
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(lat = Double.NaN), destination, now))
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(lat = 91.0), destination, now))
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(), GeoPoint(37.46, 181.0), now))
    }

    @Test fun `two minute age boundary is inclusive and future timestamps are rejected`() {
        val age = ManualArrivalReview.MAX_FIX_AGE_MILLIS
        assertEquals(ManualArrivalReview.Position.NEAR, ManualArrivalReview.position(fix(at = now - age), destination, now))
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(at = now - age - 1), destination, now))
        assertEquals(ManualArrivalReview.Position.UNKNOWN, ManualArrivalReview.position(fix(at = now + 1), destination, now))
    }

    @Test fun `request and cancellation never record an arrival`() {
        val recorded = mutableListOf<Long>()
        val controller = ManualArrivalReviewController({ true }, { recorded += it })
        assertTrue(controller.request(7, ManualArrivalReview.Position.FAR))
        assertNotNull(controller.pending.value)
        assertTrue(recorded.isEmpty())
        controller.dismiss()
        assertNull(controller.pending.value)
        assertFalse(controller.confirm())
        assertTrue(recorded.isEmpty())
    }

    @Test fun `explicit confirmation records target once even after repeated clicks`() {
        val recorded = mutableListOf<Long>()
        val controller = ManualArrivalReviewController({ true }, { recorded += it })
        controller.request(7, ManualArrivalReview.Position.UNKNOWN)
        assertTrue(controller.confirm())
        assertFalse(controller.confirm())
        assertEquals(listOf(7L), recorded)
    }

    @Test fun `expired or GPS arrived event is revalidated before confirmation`() {
        var open = true
        val recorded = mutableListOf<Long>()
        val controller = ManualArrivalReviewController({ open }, { recorded += it })
        controller.request(7, ManualArrivalReview.Position.NEAR)
        open = false
        assertFalse(controller.confirm())
        assertNull(controller.pending.value)
        assertTrue(recorded.isEmpty())
    }

    @Test fun `invalid replacement cannot leave previous event under a new notification`() {
        val recorded = mutableListOf<Long>()
        val controller = ManualArrivalReviewController({ it == 7L }, { recorded += it })
        controller.request(7, ManualArrivalReview.Position.FAR)
        assertFalse(controller.request(9, ManualArrivalReview.Position.UNKNOWN))
        assertNull(controller.pending.value)
        assertFalse(controller.request(0, ManualArrivalReview.Position.NEAR))
        assertFalse(controller.confirm())
        assertTrue(recorded.isEmpty())
    }

    @Test fun `logout dismisses review and rejects delayed request or confirmation`() {
        val recorded = mutableListOf<Long>()
        val controller = ManualArrivalReviewController({ true }, { recorded += it })
        controller.request(7, ManualArrivalReview.Position.NEAR)
        controller.endSession()
        assertNull(controller.pending.value)
        assertFalse(controller.confirm())
        assertFalse(controller.request(8, ManualArrivalReview.Position.FAR))
        assertTrue(recorded.isEmpty())
    }
}
