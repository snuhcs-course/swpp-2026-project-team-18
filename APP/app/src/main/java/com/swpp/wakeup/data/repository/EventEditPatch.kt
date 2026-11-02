package com.swpp.wakeup.data.repository

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.PlaceSearchItem
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 일정 수정 화면의 값. 원래 값과 고친 값을 같은 모양으로 두고 [EventEditPatch] 가 비교한다.
 *
 * @property origin null 이면 집에서 출발
 * @property includePrep 준비 시간을 넣는가. 켜면 true, 끄면 null(서버 기본 규칙)
 */
data class EventEditValues(
    val title: String,
    val startAt: OffsetDateTime,
    val tagKey: String?,
    val place: PlaceSearchItem?,
    val destinationIsHome: Boolean,
    val origin: PlaceSearchItem?,
    val routeKey: String?,
    val includePrep: Boolean?,
) {
    companion object {
        // Partly AI-generated (Claude)
        /** 서버 일정을 수정 화면 값으로. 집 목적지는 [homePlace] 로 채운다 */
        fun from(dto: EventDto, homePlace: PlaceSearchItem?, zone: ZoneId = ZoneId.systemDefault()): EventEditValues? {
            val start = runCatching { OffsetDateTime.parse(dto.startAt) }.getOrNull() ?: return null
            val local = start.atZoneSameInstant(zone).toOffsetDateTime()
            val place = when {
                dto.destinationIsHome -> homePlace
                else -> dto.place?.let {
                    PlaceSearchItem(
                        kakaoPlaceId = it.kakaoPlaceId,
                        name = it.name,
                        address = it.address,
                        lat = it.lat,
                        lng = it.lng,
                        category = null,
                    )
                }
            }
            val origin = dto.originLat?.let { lat ->
                dto.originLng?.let { lng ->
                    PlaceSearchItem(
                        kakaoPlaceId = null,
                        name = dto.originLabel?.takeIf { it.isNotBlank() } ?: "출발지",
                        address = null,
                        lat = lat,
                        lng = lng,
                        category = null,
                    )
                }
            }
            return EventEditValues(
                title = dto.title,
                startAt = local,
                tagKey = dto.tag?.key,
                place = place,
                destinationIsHome = dto.destinationIsHome,
                origin = origin,
                routeKey = dto.routeKey?.takeIf { it.isNotBlank() },
                // 화면 스위치는 켬(true)·끔(null) 둘뿐이다. 다른 기기가 false 로 둔 것도 끔으로 본다.
                includePrep = if (dto.includePrep == true) true else null,
            )
        }
    }
}

/**
 * `PATCH /api/events/{id}` 본문을 만든다(docs/iter3/event-edit-api.md §1·§6). 순수 함수.
 *
 * - 바뀐 필드만 넣는다. 아무것도 안 바뀌면 빈 객체다.
 * - 지우기는 JSON `null` 을 실제로 넣는다(기본 Gson 은 null 필드를 빼서 "그대로" 가 된다).
 *   그래서 이 객체는 `toString()` 으로 직렬화해 보낸다. `route_key` 지우기는 `""` 다.
 * - 장소나 출발지가 바뀌면 경로를 늘 함께 보낸다. 다시 고른 key, 고르지 않았으면 `""`(서버가
 *   빠른 쪽을 고름). 옛 대중교통 key 를 두면 서버가 새 구간에서 같은 노선을 찾다 바꾼다.
 */
object EventEditPatch {

    // Partly AI-generated (Claude)
    fun build(base: EventEditValues, edited: EventEditValues): JsonObject {
        val body = JsonObject()

        val title = edited.title.trim()
        if (title != base.title.trim()) body.addProperty("title", title)

        if (!edited.startAt.isEqual(base.startAt)) body.addProperty("start_at", edited.startAt.toString())

        if (edited.tagKey != base.tagKey) {
            if (edited.tagKey == null) body.add("tag_key", JsonNull.INSTANCE) else body.addProperty("tag_key", edited.tagKey)
        }

        val destinationChanged = destinationChanged(base, edited)
        if (destinationChanged) {
            val place = edited.place
            when {
                edited.destinationIsHome -> body.addProperty("destination_is_home", true)
                place != null -> body.add("place", placeJson(place))
                else -> {
                    if (base.destinationIsHome) body.addProperty("destination_is_home", false)
                    body.add("place", JsonNull.INSTANCE)
                }
            }
        }

        val originChanged = !samePoint(base.origin, edited.origin)
        if (originChanged) {
            val origin = edited.origin
            if (origin == null) {
                body.add("origin_lat", JsonNull.INSTANCE)
                body.add("origin_lng", JsonNull.INSTANCE)
                body.addProperty("origin_label", "")
            } else {
                body.addProperty("origin_lat", origin.lat)
                body.addProperty("origin_lng", origin.lng)
                body.addProperty("origin_label", origin.name.take(ORIGIN_LABEL_MAX))
            }
        }

        if (destinationChanged || originChanged || edited.routeKey != base.routeKey) {
            body.addProperty("route_key", edited.routeKey.orEmpty())
        }

        // 집에서 출발하면 스위치가 없다. 켜 두었던 값은 지운다.
        val includePrep = edited.includePrep.takeIf { edited.origin != null }
        if (includePrep != base.includePrep) {
            if (includePrep == true) body.addProperty("include_prep", true) else body.add("include_prep", JsonNull.INSTANCE)
        }
        return body
    }

    private fun destinationChanged(base: EventEditValues, edited: EventEditValues): Boolean = when {
        edited.destinationIsHome != base.destinationIsHome -> true
        edited.destinationIsHome -> false
        else -> !samePoint(base.place, edited.place)
    }

    /** 서버는 값으로 비교한다(좌표). 이름만 다른 같은 장소는 바뀐 것이 아니다. */
    private fun samePoint(a: PlaceSearchItem?, b: PlaceSearchItem?): Boolean = when {
        a == null || b == null -> a == null && b == null
        else -> abs(a.lat - b.lat) < COORD_EPS && abs(a.lng - b.lng) < COORD_EPS
    }

    private fun placeJson(place: PlaceSearchItem) = JsonObject().apply {
        addProperty("name", place.name)
        addProperty("lat", place.lat)
        addProperty("lng", place.lng)
        place.address?.takeIf { it.isNotBlank() }?.let { addProperty("address", it) }
        place.kakaoPlaceId?.takeIf { it.isNotBlank() }?.let { addProperty("kakao_place_id", it) }
    }

    private const val COORD_EPS = 1e-7
    private const val ORIGIN_LABEL_MAX = 80
}
