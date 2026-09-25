package com.swpp.wakeup.sensing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 경로 호출이 화면 수명으로 다시 이동하는 회귀를 막는다. */
class TripLiveRouteBackgroundContractTest {

    @Test
    fun `실시간 경로 API는 포그라운드 추적 서비스가 소유한다`() {
        val service = readSource("sensing/TripTrackingService.kt")
        val viewModel = readSource("ui/home/HomeViewModel.kt")
        val activity = readSource("MainActivity.kt")

        assertTrue(service.contains("repository.liveRoute(current.eventId, here)"))
        assertTrue(service.contains("START_REDELIVER_INTENT"))
        assertFalse(viewModel.contains("repository.liveRoute("))
        assertFalse(viewModel.contains("onAlarmDecisionVisibilityChanged"))
        assertFalse(activity.contains("AlarmDecisionVisibilityEffect"))
    }

    @Test
    fun `로그아웃은 경로 사본을 지우기 전에 추적 서비스를 멈춘다`() {
        val stores = readSource("data/local/LocalStores.kt")
        val stop = stores.indexOf("TripTrackingService.stop(app)")
        val wipe = stores.indexOf("LiveRouteStore(app).wipe()")

        assertTrue("추적 서비스 중지가 없다", stop >= 0)
        assertTrue("실시간 경로 사본 삭제가 없다", wipe >= 0)
        assertTrue("사본을 지운 뒤 서비스가 다시 쓰지 않도록 먼저 멈춰야 한다", stop < wipe)
    }

    @Test
    fun `화면 복귀 시 저장 경로가 현재 위치 기준 주 경로가 된다`() {
        val viewModel = readSource("ui/home/HomeViewModel.kt")

        assertTrue(viewModel.contains("cachedRoute != null -> true"))
        assertTrue(viewModel.contains("path = usable.path"))
        assertTrue(viewModel.contains("pathFromCurrent = true"))
        assertFalse(viewModel.contains("altPath = live.path"))
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) fail("$relative 을 찾지 못했다")
        return found!!.readText()
    }
}
