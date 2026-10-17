// AI-generated (Claude)
package com.swpp.wakeup.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ④ 이동 줄의 첫 마디. 카카오 추정치를 "실측" 처럼 읽게 하지 않는다.
 * 출처 꼬리(`+observed_route`/`+observed_global`)는 서버 `estimate_travel` 이 붙인다.
 */
class TravelSourceNoteTest {

    @Test
    fun `보정 꼬리에 따라 문구가 갈린다`() {
        assertEquals("카카오 추정 · 이 경로 이동 기록으로 보정", travelSourceNote("kakao_transit+observed_route"))
        assertEquals("카카오 추정 · 전체 이동 기록으로 보정", travelSourceNote("kakao_walk+observed_global"))
        assertEquals("카카오 경로 추정", travelSourceNote("kakao_transit"))
    }

    @Test
    fun `출처가 없거나 비어도 추정이라고 쓴다`() {
        assertEquals("카카오 경로 추정", travelSourceNote(null))
        assertEquals("카카오 경로 추정", travelSourceNote(""))
    }

    @Test
    fun `실측이라는 말을 쓰지 않는다`() {
        listOf(null, "", "kakao_transit", "kakao_car+observed_route", "kakao_walk+observed_global")
            .forEach { assertFalse(travelSourceNote(it).contains("실측")) }
    }
}

/**
 * τ 출처. 서버 `Event.effective_tau` 순서(직접 고른 값 → 일정 종류 → 프로필 기본값).
 * 종류 없는 일정(캘린더 가져오기·"기타")에 "일정 종류에서 결정됨" 이라고 쓰면 틀린다.
 */
class TauSourceLabelTest {

    @Test
    fun `직접 고른 값이 종류보다 앞선다`() {
        assertEquals("직접 고른 값", tauSourceLabel(tauOverride = 0.97, tagLabel = "수업"))
    }

    @Test
    fun `종류가 있으면 그 종류의 기본값이다`() {
        assertEquals("수업 일정 기본값", tauSourceLabel(tauOverride = null, tagLabel = "수업"))
    }

    @Test
    fun `종류가 없으면 기본값이다`() {
        assertEquals("기본값", tauSourceLabel(tauOverride = null, tagLabel = null))
        assertEquals("기본값", tauSourceLabel(tauOverride = null, tagLabel = " "))
    }
}
