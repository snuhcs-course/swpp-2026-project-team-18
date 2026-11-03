package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import com.swpp.wakeup.data.remote.TripObservationInput
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 판정 결과 → 업로드할 관측 한 건. [TripTrackingService] 의 `record` 가 부른다.
 *
 * 서비스에서 떼어 둔 이유는 JVM 테스트다. 서비스는 위치·알림이 있어야 돌지만 이 변환은
 * 값만 다룬다. 진입 시각(db-schema.md A1)이 큐까지 닿는지를 여기서 확인한다.
 *
 * - [dwellMillis] 와 [enteredAtMillis] 는 도착에만 있다. 출발은 "반경을 벗어남" 이라
 *   머문 시간도 진입 시각도 없다.
 * - 진입 시각이 판정보다 늦으면 보내지 않는다. 서버가 그 관측을 400 으로 막고, 400 이면
 *   큐가 그 배치를 통째로 버린다 — 진입 시각 하나 때문에 도착 기록을 잃지 않는다.
 * - [clientUuid] 는 판정 시점에 만든다. 재전송할 때 새로 만들면 멱등성이 깨진다.
 */
internal fun tripObservationOf(
    eventId: Long,
    kind: String,
    fix: LocationFix,
    distanceM: Double,
    dwellMillis: Long? = null,
    enteredAtMillis: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    clientUuid: String = UUID.randomUUID().toString(),
): TripObservationInput {
    val arrival = kind == TripObservationInput.KIND_ARRIVE
    return TripObservationInput(
        event = eventId,
        kind = kind,
        observedAt = isoAt(fix.atMillis, zone),
        lat = fix.lat,
        lng = fix.lng,
        accuracyM = fix.accuracyM.toDouble(),
        distanceM = distanceM,
        dwellSeconds = dwellMillis?.takeIf { arrival }?.let { (it / 1000).toInt() },
        clientUuid = clientUuid,
        enteredAt = enteredAtMillis
            ?.takeIf { arrival && it <= fix.atMillis }
            ?.let { isoAt(it, zone) },
    )
}

/**
 * "도착했어요" 를 누른 것. `detector=manual` 도착 관측이다(백엔드 P0 수동 도착).
 *
 * GPS 가 도착을 못 잡았을 때의 보조다. 서버는 GPS 도착이 있으면 그쪽을 먼저 쓰고, 시작 + 1시간
 * 뒤에 누른 것은 읽을 때 거른다. 최근 위치가 있으면 같이 보낸다(오차는 따지지 않는다).
 * 위치가 없으면 lat·lng 를 둘 다 뺀다. 목적지를 모르면 거리도 뺀다.
 *
 * @param fix [FIX_MAX_AGE_MILLIS] 안의 최근 위치. 없으면 null
 */
internal fun manualArrivalOf(
    eventId: Long,
    atMillis: Long,
    fix: LocationFix?,
    destination: GeoPoint?,
    zone: ZoneId = ZoneId.systemDefault(),
    clientUuid: String = UUID.randomUUID().toString(),
): TripObservationInput {
    val usable = fix?.takeIf {
        it.lat.isFinite() && it.lng.isFinite() && atMillis - it.atMillis in 0..FIX_MAX_AGE_MILLIS
    }
    return TripObservationInput(
        event = eventId,
        kind = TripObservationInput.KIND_ARRIVE,
        detector = TripObservationInput.DETECTOR_MANUAL,
        observedAt = isoAt(atMillis, zone),
        lat = usable?.lat,
        lng = usable?.lng,
        accuracyM = usable?.accuracyM?.takeIf { it.isFinite() && it >= 0f }?.toDouble(),
        distanceM = usable?.let { f -> destination?.let { TripGeofence.distanceMeters(f.point, it) } },
        clientUuid = clientUuid,
    )
}

/** 수동 도착에 같이 보낼 위치의 최대 나이. 이보다 오래된 위치는 지금 있는 곳을 말하지 않는다 */
internal const val FIX_MAX_AGE_MILLIS = 2 * 60_000L

/** 서버가 읽는 시각 문자열. 오프셋을 붙인다(ISO 8601). */
internal fun isoAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()
