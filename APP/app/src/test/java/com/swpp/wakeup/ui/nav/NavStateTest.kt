package com.swpp.wakeup.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// AI-generated (Claude)
/**
 * 화면 스택 규칙. 두 번 눌러도 한 장만 쌓이고, 기다린 결과는 그 결과의 화면만 닫는다 —
 * 기다리는 동안 사용자가 옮겨 간 화면을 닫지 않는다.
 */
class NavStateTest {

    private fun nav(vararg routes: AppRoute) = NavState(stack = listOf(AppRoute.Home) + routes)

    // --- push -------------------------------------------------------------

    @Test
    fun `새 화면을 맨 위에 쌓고 앞으로 가는 전환이다`() {
        // Given 홈
        val home = NavState(forward = false)

        // When
        val next = home.push(AppRoute.Settings)

        // Then
        assertEquals(listOf(AppRoute.Home, AppRoute.Settings), next.stack)
        assertTrue(next.forward)
    }

    @Test
    fun `맨 위와 같은 화면을 또 쌓으면 그대로다`() {
        // Given 일정 행을 눌러 알람 결정이 열렸다
        val opened = nav(AppRoute.AlarmDecision(5))

        // When 전환 중에 한 번 더 눌렀다
        val again = opened.push(AppRoute.AlarmDecision(5))

        // Then 한 장뿐이라 뒤로 한 번에 홈이다
        assertSame(opened, again)
        assertEquals(listOf(AppRoute.Home), again.popIf { it is AppRoute.AlarmDecision }.stack)
    }

    @Test
    fun `일정 수정을 두 번 받아도 수정 화면은 한 장이다`() {
        // Given 첫 응답으로 수정 화면이 열렸다
        val editing = nav(AppRoute.AlarmDecision(5)).push(AppRoute.EditEvent(5))

        // When 두 번째 응답이 같은 화면을 쌓으려 한다
        val twice = editing.push(AppRoute.EditEvent(5))

        // Then
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5)), twice.stack)
    }

    @Test
    fun `맨 위가 아닌 같은 화면이나 다른 일정의 같은 화면은 쌓는다`() {
        val stack = nav(AppRoute.AlarmDecision(5), AppRoute.MorningProgress)

        assertEquals(4, stack.push(AppRoute.AlarmDecision(5)).stack.size)
        assertEquals(
            AppRoute.AlarmDecision(7),
            nav(AppRoute.AlarmDecision(5)).push(AppRoute.AlarmDecision(7)).current,
        )
    }

    // --- popIf ------------------------------------------------------------

    @Test
    fun `맨 위가 맞으면 한 칸 닫고 뒤로 가는 전환이다`() {
        // Given 수정 저장을 기다리는 중
        val editing = nav(AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))

        // When 저장이 끝났다
        val closed = editing.popIf { it is AppRoute.EditEvent }

        // Then 알람 결정으로 돌아간다
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(5)), closed.stack)
        assertFalse(closed.forward)
    }

    @Test
    fun `기다리는 동안 그 화면을 떠났으면 popIf 는 아무것도 닫지 않는다`() {
        // Given 저장을 기다리다 뒤로 나가 알람 결정에 있다
        val left = nav(AppRoute.AlarmDecision(5))

        // When 늦게 저장이 끝났다
        val after = left.popIf { it is AppRoute.EditEvent }

        // Then 알람 결정이 닫히지 않는다
        assertSame(left, after)
    }

    @Test
    fun `popIf 는 홈을 닫지 않는다`() {
        val home = NavState()
        assertSame(home, home.popIf { true })
    }

    // --- close ------------------------------------------------------------

    @Test
    fun `close 는 맨 위면 popIf 와 같다`() {
        val stack = nav(AppRoute.Settings, AppRoute.HomeSetup)
        assertEquals(stack.popIf { it == AppRoute.HomeSetup }, stack.close { it == AppRoute.HomeSetup })
    }

    @Test
    fun `저장하는 동안 위에 다른 화면이 쌓였으면 그 화면은 두고 저장한 화면만 뺀다`() {
        // Given 수정 저장 중에 알림이 다른 일정의 알람 결정을 위에 열었다
        val stack = nav(AppRoute.AlarmDecision(5), AppRoute.EditEvent(5), AppRoute.AlarmDecision(9))
            .copy(forward = true)

        // When 저장이 끝났다
        val closed = stack.close { it == AppRoute.EditEvent(5) }

        // Then 보고 있던 화면은 그대로고, 뒤로 가면 비워진 폼이 아니라 알람 결정이다
        assertEquals(
            listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.AlarmDecision(9)),
            closed.stack,
        )
        assertEquals(AppRoute.AlarmDecision(9), closed.current)
        assertTrue("맨 위가 그대로라 전환 방향도 그대로다", closed.forward)
    }

    // AI-generated (Claude)
    @Test
    fun `뺀 자리 양옆이 같은 화면이면 한 장만 남긴다`() {
        // Given 수정 저장 중에 같은 일정의 경로 바꾸기 알림이 같은 알람 결정을 위에 열었다
        val edit = nav(AppRoute.AlarmDecision(5), AppRoute.EditEvent(5), AppRoute.AlarmDecision(5))
            .copy(forward = true)
        // 지각 위험 저장 중에도 같은 일이 생길 수 있다
        val risk = nav(AppRoute.AlarmDecision(5), AppRoute.RiskChoice(5), AppRoute.AlarmDecision(5))

        // When 저장이 끝나 그 화면을 스택에서 뺀다
        val afterEdit = edit.close { it == AppRoute.EditEvent(5) }
        val afterRisk = risk.close { it == AppRoute.RiskChoice(5) }

        // Then 같은 화면 두 장이 붙어 남지 않는다 — 남으면 전환 키가 같아 뒤로를 한 번 눌러도 바뀌는 것이 없어 보인다
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(5)), afterEdit.stack)
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(5)), afterRisk.stack)
        assertTrue("보던 화면이 그대로라 전환 방향도 그대로다", afterEdit.forward)
    }

    @Test
    fun `여러 장이 맞으면 가장 위의 것 하나만 뺀다`() {
        val stack = nav(AppRoute.HomeSetup, AppRoute.Settings, AppRoute.HomeSetup, AppRoute.MorningProgress)

        val closed = stack.close { it == AppRoute.HomeSetup }

        assertEquals(
            listOf(AppRoute.Home, AppRoute.HomeSetup, AppRoute.Settings, AppRoute.MorningProgress),
            closed.stack,
        )
    }

    @Test
    fun `이미 닫힌 화면이면 close 는 그대로고 홈은 빼지 않는다`() {
        // Given 실패한 일정 추가를 떠나 홈에서 스낵바의 다시 시도를 눌렀다
        val home = NavState()
        val settings = nav(AppRoute.Settings)

        // Then 저장이 끝나도 보고 있던 화면이 닫히지 않는다
        assertSame(home, home.close { it == AppRoute.AddEvent })
        assertSame(settings, settings.close { it == AppRoute.AddEvent })
        assertSame(home, home.close { it == AppRoute.Home })
    }
}
