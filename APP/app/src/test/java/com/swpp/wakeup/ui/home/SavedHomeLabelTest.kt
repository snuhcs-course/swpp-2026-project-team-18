// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.SavedPlacesResult
import com.swpp.wakeup.domain.model.SavedHomeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 저장한 집 이름 겹침(C3, BE-H9). 비교 규칙은 서버 계약 1절과 같다 */
class SavedHomeLabelTest {

    @Test
    fun `비교 키 - NFKC · 서식 문자 · 공백 · 대소문자`() {
        assertEquals(SavedHomeLabel.key("HOME"), SavedHomeLabel.key("ＨＯＭＥ"))
        assertEquals(SavedHomeLabel.key("집"), SavedHomeLabel.key("집​"))
        assertEquals(SavedHomeLabel.key("우리 집"), SavedHomeLabel.key("  우리 \t 집 "))
        assertEquals(SavedHomeLabel.key("home"), SavedHomeLabel.key("Home"))
        assertNotEquals("공백을 없애지는 않는다", SavedHomeLabel.key("본가2"), SavedHomeLabel.key("본가 2"))
    }

    @Test
    fun `저장 전 검사 - 빈 이름 · 집 · 겹침`() {
        val others = listOf("본가", "기숙사")
        assertEquals(SavedHomeLabel.Problem.EMPTY, SavedHomeLabel.problem(" ​ ", others))
        assertEquals(SavedHomeLabel.Problem.RESERVED, SavedHomeLabel.problem(" 집 ", others))
        assertEquals(SavedHomeLabel.Problem.DUPLICATE, SavedHomeLabel.problem("본가 ", others))
        assertEquals(SavedHomeLabel.Problem.DUPLICATE, SavedHomeLabel.problem("기숙사‍", others))
        assertNull(SavedHomeLabel.problem("본가 2", others))
        assertEquals("같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요.", SavedHomeLabel.Problem.DUPLICATE.message)
    }

    @Test
    fun `409 이름 겹침은 이름 칸 아래에 서버 문구로`() {
        val failure = SavedPlacesResult.Failure(
            "같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요.",
            code = SavedPlacesRepository.CODE_LABEL_DUPLICATE,
            status = 409,
            fields = mapOf("label" to "'집'은 기본 집 이름이라 쓸 수 없어요. 다른 이름을 써 주세요."),
        )
        val next = HomeViewModel.HomeSetupState(submitting = true, error = "old").withAddFailure(failure)
        assertEquals("'집'은 기본 집 이름이라 쓸 수 없어요. 다른 이름을 써 주세요.", next.labelError)
        assertNull(next.error)
    }

    @Test
    fun `빈 이름 400 도 이름 칸 아래로 - 좌표 400 은 폼 줄 그대로`() {
        val empty = SavedPlacesResult.Failure("이름을 입력해 주세요.", code = "invalid", status = 400, fields = mapOf("label" to "이름을 입력해 주세요."))
        val e = HomeViewModel.HomeSetupState(submitting = true).withAddFailure(empty)
        assertEquals("이름을 입력해 주세요.", e.labelError)
        assertNull(e.error)

        val lat = SavedPlacesResult.Failure("서버", code = "invalid", status = 400, fields = mapOf("lat" to "국내 좌표만 집으로 저장할 수 있어요."))
        val l = HomeViewModel.HomeSetupState(submitting = true).withAddFailure(lat)
        assertEquals("국내 좌표만 집으로 저장할 수 있어요.", l.error)
        assertNull(l.labelError)
    }

    @Test
    fun `bulk 이름 바꿈 안내`() {
        assertEquals("같은 이름이 있어 '본가 2'로 바꿨어요", SavedHomeLabel.renamedNotice(listOf("본가 2")))
        assertEquals("같은 이름이 있어 '예전 집 3'으로 바꿨어요", SavedHomeLabel.renamedNotice(listOf("예전 집 3")))
        assertEquals("같은 이름이 있어 2곳의 이름에 번호를 붙였어요", SavedHomeLabel.renamedNotice(listOf("본가 2", "집 3")))
        assertNull(SavedHomeLabel.renamedNotice(emptyList()))
    }
}
