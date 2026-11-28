package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.sensing.LocationFix
import com.swpp.wakeup.sensing.TripGeofence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All position checks remain on device. This result contains no coordinates. */
object ManualArrivalReview {
    enum class Position { NEAR, FAR, UNKNOWN }

    const val MAX_FIX_AGE_MILLIS = 2 * 60_000L

    fun position(fix: LocationFix?, destination: GeoPoint?, nowMillis: Long): Position {
        if (fix == null || destination == null || !validPoint(fix.point) || !validPoint(destination)) {
            return Position.UNKNOWN
        }
        if (nowMillis - fix.atMillis !in 0..MAX_FIX_AGE_MILLIS ||
            !TripGeofence.isUsable(fix)
        ) return Position.UNKNOWN
        val distance = TripGeofence.distanceMeters(fix.point, destination)
        return if (distance - fix.accuracyM > TripGeofence.DEFAULT_ARRIVAL_RADIUS_M) {
            Position.FAR
        } else if (distance + fix.accuracyM <= TripGeofence.DEFAULT_ARRIVAL_RADIUS_M) {
            Position.NEAR
        } else Position.UNKNOWN
    }

    private fun validPoint(point: GeoPoint): Boolean =
        point.lat.isFinite() && point.lng.isFinite() && point.lat in -90.0..90.0 && point.lng in -180.0..180.0
}

/** The app and notification both require an explicit review before recording. */
internal class ManualArrivalReviewController(
    private val canRecord: (Long) -> Boolean,
    private val record: (Long) -> Unit,
) {
    data class Pending(val eventId: Long, val position: ManualArrivalReview.Position)
    private val mutable = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = mutable.asStateFlow()
    private var ended = false

    fun request(eventId: Long, position: ManualArrivalReview.Position): Boolean {
        mutable.value = null
        if (ended || eventId <= 0 || !canRecord(eventId)) return false
        mutable.value = Pending(eventId, position)
        return true
    }

    fun dismiss() { mutable.value = null }

    /** Revalidate after the dialog: GPS may have arrived or the window expired. */
    fun confirm(): Boolean {
        val review = mutable.value ?: return false
        mutable.value = null
        if (ended || !canRecord(review.eventId)) return false
        record(review.eventId)
        return true
    }

    fun endSession() {
        ended = true
        mutable.value = null
    }
}
