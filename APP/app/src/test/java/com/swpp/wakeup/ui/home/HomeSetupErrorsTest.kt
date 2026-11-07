// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.SavedPlacesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 다른 집 저장 실패가 ⑭ 의 어느 줄에 보이는가(Figma F-50 `528:105`, 리뷰 m3). */
class HomeSetupErrorsTest {
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
}
