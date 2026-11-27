package com.swpp.wakeup.sensing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 이전 빌드의 알림·캐시 정리 계약. 현재 위치 경로 호출 차단은 저장소 동작 테스트에서 확인한다. */
class TripLiveRouteBackgroundContractTest {

    @Test
    fun `로그아웃은 경로 사본을 지우기 전에 추적 서비스를 멈춘다`() {
        val stores = readSource("data/local/LocalStores.kt")
        val stop = stores.indexOf("TripTrackingService.stop(app)")
        val wipe = stores.indexOf("LiveRouteStore(app).wipe()")

        assertTrue("추적 서비스 중지가 없다", stop >= 0)
        assertTrue("실시간 경로 사본 삭제가 없다", wipe >= 0)
        assertTrue("사본을 지운 뒤 서비스가 다시 쓰지 않도록 먼저 멈춰야 한다", stop < wipe)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `알림에서 경로를 바꾸면 화면이 따라가도록 기록한다`() {
        val service = readSource("sensing/TripTrackingService.kt")
        val start = service.indexOf("private fun handleReroute(")
        assertTrue(start >= 0)
        assertTrue(service.substring(start).contains("TripLiveState.recordReroute("))
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) fail("$relative 을 찾지 못했다")
        return found!!.readText()
    }
}
