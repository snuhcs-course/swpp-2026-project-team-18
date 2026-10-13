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

/** 서버가 읽는 시각 문자열. 오프셋을 붙인다(ISO 8601). */
internal fun isoAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()
