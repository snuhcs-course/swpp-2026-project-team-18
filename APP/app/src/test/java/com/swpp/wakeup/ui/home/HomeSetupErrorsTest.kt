// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.SavedPlacesResult
import com.swpp.wakeup.domain.model.SavedHomes
import com.swpp.wakeup.domain.model.codePointLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 다른 집 저장 실패가 ⑭ 의 어느 줄에 보이는가(Figma F-50 `528:105`, 리뷰 m3). */
class HomeSetupErrorsTest {
    private val sameAsDefault = SavedPlacesResult.Failure(
        "서버 문구",
        code = SavedPlacesRepository.CODE_SAME_AS_DEFAULT,
        status = 409,
    )

    private val otherPlace = PlaceSearchItem(
        kakaoPlaceId = "1",
        name = "기숙사",
        address = "관악구",
        lat = 37.46,
        lng = 126.95,
        category = null,
    )

    @Test
    fun `same_as_default 는 이름 칸 아래 문구로 가고 폼 오류 줄은 비운다`() {
        val failure = SavedPlacesResult.Failure(
            "서버 문구",
            code = SavedPlacesRepository.CODE_SAME_AS_DEFAULT,
            status = 409,
        )

        val next = HomeViewModel.HomeSetupState(submitting = true, error = "old").withAddFailure(failure)

        assertEquals("기본 집과 같은 곳이에요(50m 안). 다른 곳을 골라 주세요", next.labelError)
        assertNull(next.error)
        assertFalse(next.submitting)
    }

    @Test
    fun `saved_place_limit 은 폼 오류 줄에 남고 이름 칸 문구를 지운다`() {
        val failure = SavedPlacesResult.Failure("서버 문구", code = "saved_place_limit", status = 409)

        val next = HomeViewModel.HomeSetupState(submitting = true, labelError = "old").withAddFailure(failure)

        assertEquals("다른 집은 5곳까지 저장할 수 있어요", next.error)
        assertNull(next.labelError)
        assertFalse(next.submitting)
    }

    @Test
    fun `네트워크 실패는 폼 오류 줄에 남는다`() {
        val failure = SavedPlacesResult.Failure("연결 실패", network = true)

        val next = HomeViewModel.HomeSetupState(submitting = true, labelError = "old").withAddFailure(failure)

        assertEquals("서버에 연결하지 못해 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요", next.error)
        assertNull(next.labelError)
    }

    // --- 이름 칸 고치기 (AI-generated (Claude)) -------------------------------

    @Test
    fun `기본 집과 같은 곳이라는 문구는 이름을 고쳐도 남는다`() {
        // Given 이름을 "집" 으로 적고 기본 집 주소를 골라 거절됐다
        val rejected = HomeViewModel.HomeSetupState(label = "집", submitting = true).withAddFailure(sameAsDefault)

        // When 이름이 문제인 줄 알고 이름을 고친다
        val next = rejected.withLabel("기숙사")

        // Then 주소가 겹친다는 문구는 주소를 바꿀 때까지 남는다
        assertEquals("기숙사", next.label)
        assertEquals("기본 집과 같은 곳이에요(50m 안). 다른 곳을 골라 주세요", next.labelError)
    }

    @Test
    fun `다른 곳을 고르면 기본 집과 같은 곳이라는 문구를 지운다`() {
        // Given
        val rejected = HomeViewModel.HomeSetupState().withAddFailure(sameAsDefault)

        // When
        val next = rejected.withPlace(otherPlace)

        // Then
        assertEquals(otherPlace, next.selected)
        assertNull(next.labelError)
    }

    @Test
    fun `이름을 고치면 폼 아래 오류 줄은 지운다`() {
        // Given 다섯 곳 한도로 거절됐다
        val failure = SavedPlacesResult.Failure("서버 문구", code = "saved_place_limit", status = 409)
        val rejected = HomeViewModel.HomeSetupState(submitting = true).withAddFailure(failure)

        // When / Then
        assertNull(rejected.withLabel("본가").error)
    }

    @Test
    fun `이름은 12자까지 두고 이모지를 반으로 자르지 않는다`() {
        // Given 열한 글자 뒤에 이모지 둘(13자, UTF-16 으로는 15단위)
        val typed = "가".repeat(11) + "😀😀"

        // When
        val next = HomeViewModel.HomeSetupState().withLabel(typed)

        // Then 열두 글자까지 남기고 이모지는 통째로 남는다
        assertEquals("가".repeat(11) + "😀", next.label)
        assertEquals(SavedHomes.LABEL_MAX, next.label.codePointLength())
    }
}
