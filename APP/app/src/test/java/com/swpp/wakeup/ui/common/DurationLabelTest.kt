// AI-generated (Claude)
package com.swpp.wakeup.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 준비 시간 표기. 다이얼(시:분)·설정 행·저장 스낵바가 같은 표기를 쓴다.
 * 범위 안내 "5분~4시간 사이로 고름" 도 이 함수로 만든다.
 */
class DurationLabelTest {

    @Test
    fun `한 시간 미만은 분만 쓴다`() {
        assertEquals("5분", durationLabel(5))
        assertEquals("35분", durationLabel(35))
    }

    @Test
    fun `정시는 시간만 쓴다`() {
        assertEquals("1시간", durationLabel(60))
        assertEquals("2시간", durationLabel(120))
        assertEquals("4시간", durationLabel(240))
    }

    @Test
    fun `시간과 분이 모두 있으면 둘 다 쓴다`() {
        assertEquals("1시간 5분", durationLabel(65))
    }
}
