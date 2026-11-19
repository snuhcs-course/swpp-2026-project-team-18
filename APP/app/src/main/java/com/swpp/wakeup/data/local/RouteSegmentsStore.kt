// Partly AI-generated (Claude)
package com.swpp.wakeup.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments

/**
 * 이동 탭 "가는 길" 구간(노선 · 정류장 · 구간 시간)의 기기 사본(11-17 사용자: 인터넷이 없으면 가는 길이 비었다).
 *
 * 구간은 경로 후보 응답에만 있어 매번 서버(카카오)에서 받았고, 어디에도 남기지 않아 오프라인이면 비었다. 받을 때마다
 * 일정별로 덮어쓰고, 못 받으면 이것을 "N분 전 정보" 로 보인다. **실시간 도착(곧 도착 · n분 후)은 남기지 않는다** —
 * 지난 실시간 값은 틀린 값이다. 사흘이 지난 사본은 쓸 때 지운다(일정이 끝난 뒤 남지 않게).
 */
class RouteSegmentsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Saved(val routeKey: String, val savedAtMillis: Long, val items: List<RouteSegment>) {
        val segments: RouteSegments get() = RouteSegments(items)
    }

    /** [eventId] 의 [routeKey] 사본. 경로가 바뀌었거나 없으면 null */
    fun get(eventId: Long, routeKey: String): Saved? {
        if (prefs.discardIfForeign(appContext)) return null
        return all()[eventId.toString()]?.takeIf { it.routeKey == routeKey && it.items.isNotEmpty() }
    }

    /** 새로 받은 구간으로 덮어쓴다. 실시간 도착은 빼고 남긴다. 로그아웃 상태면 false */
    fun save(eventId: Long, routeKey: String, segments: RouteSegments, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val next = all()
            .filterValues { nowMillis - it.savedAtMillis < MAX_AGE_MILLIS }
            .toMutableMap()
        next[eventId.toString()] = Saved(routeKey, nowMillis, segments.items.map(::withoutLive))
        return prefs.editOwned(appContext) { putString(KEY, gson.toJson(next)) }
    }

    /** 일정이 지워졌거나 끝났다 */
    fun remove(eventId: Long) {
        val next = all() - eventId.toString()
        prefs.editOwned(appContext) { putString(KEY, gson.toJson(next)) }
    }

    /** 로그아웃 · 계정 전환 */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    private fun all(): Map<String, Saved> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, Saved>>(raw, object : TypeToken<Map<String, Saved>>() {}.type) ?: emptyMap()
        } catch (e: JsonParseException) {
            emptyMap()
        }
    }

    companion object {
        private const val FILE = "jit_route_segments"
        private const val KEY = "segments"

        /** 이보다 오래된 사본은 지운다 */
        const val MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** 실시간 값(도착 예정 열차 · 버스)을 뺀 구간. 노선 · 정류장 · 시간 · 배차는 남긴다 */
        fun withoutLive(segment: RouteSegment): RouteSegment =
            segment.copy(arrivals = emptyList(), alternatives = segment.alternatives.map(::withoutLive))
    }
}
