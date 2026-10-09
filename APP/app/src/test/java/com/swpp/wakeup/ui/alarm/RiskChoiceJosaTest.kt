// AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 리스크 선택 하단 버튼 "{선택}으로 계속하기" 의 조사.
 *
 * 옵션 라벨은 서버가 정한다(B-5). "안전·보통·도박" 은 모두 받침이 있지만 다른
 * 라벨이 오면 "여유으로" 처럼 틀린 문장이 된다.
 */
class RiskChoiceJosaTest {

    @Test
    fun `받침이 있으면 으로`() {
        assertEquals("으로", josaRo("안전"))
        assertEquals("으로", josaRo("보통"))
        assertEquals("으로", josaRo("도박"))
    }

    @Test
    fun `받침이 없으면 로`() {
        assertEquals("로", josaRo("여유"))
    }

    @Test
    fun `ㄹ 받침은 로`() {
        assertEquals("로", josaRo("일찍 출발"))
        assertEquals("로", josaRo("서울"))
    }

    @Test
    fun `한글이 아니거나 비어 있으면 로`() {
        assertEquals("로", josaRo("A"))
        assertEquals("로", josaRo(""))
    }
}
