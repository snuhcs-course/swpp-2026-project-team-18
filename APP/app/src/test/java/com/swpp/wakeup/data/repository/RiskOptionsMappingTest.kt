package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventUpdateRequest
import com.swpp.wakeup.data.remote.EventsApi
import com.swpp.wakeup.data.remote.ProfileApi
import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.RiskOptionsView
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy
import java.time.ZoneId

class RiskOptionsMappingTest {
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Seoul")
    private val json = """{
        "options":[
            {"key":"safe","label":"안전","tau":0.97,"alarm_at":"2026-10-19T22:20:00Z","probability":99},
            {"key":"normal","label":"보통","tau":0.90,"alarm_at":"2026-10-19T22:40:00Z","probability":null}
        ],
        "selected_key":null,"auto_adjusted":false,"auto_reason":null,
        "distribution":{"source":"model","window_days":null,"p50":45,"p90":59,
            "bins":[{"min":0,"max":40,"mass":0.2},{"min":40,"max":80,"mass":0.8}]},
        "unavailable_reason":null
    }"""

    @Test
    fun `server times tau unknown probability and modeled mass survive mapping`() {
        val view = gson.fromJson(json, RiskOptionsDto::class.java).toView(zone)
        assertEquals("7:20", view.options[0].alarmAt)
        assertEquals("AM", view.options[0].meridiem)
        assertEquals(.97, view.options[0].tau, .00001)
        assertEquals(99, view.options[0].probability)
        assertNull(view.options[1].probability)
        assertNull(view.options[0].sleepLabel)
        assertEquals("model", view.distribution!!.source)
        assertNull(view.distribution.windowDays)
        assertEquals(.8, view.distribution.bins[1].mass, .00001)
    }

    @Test
    fun `custom tau leaves every choice unselected until user chooses a key`() {
        val view = gson.fromJson(json, RiskOptionsDto::class.java).toView(zone)
        assertNull(view.selectedKey)
        assertNull(view.option(view.selectedKey))
        assertNull(view.option("unknown"))
        assertEquals(.97, view.option("safe")!!.tau, .00001)
    }

    @Test
    fun `empty options preserve the server reason without automatic guidance`() {
        val dto = gson.fromJson("""{"options":[],"selected_key":null,"auto_adjusted":false,
            "auto_reason":null,"distribution":null,"unavailable_reason":"저장된 경로 원값이 없습니다."}""",
            RiskOptionsDto::class.java)
        val view = dto.toView(zone)
        assertTrue(view.options.isEmpty())
        assertEquals(dto.unavailableReason, view.unavailableReason)
        assertFalse(view.autoAdjusted)
        assertNull(view.distribution)
    }

    @Test
    fun `repository fetches choices read only and saves exact server tau through PATCH`() = runTest {
        val calls = mutableListOf<String>()
        val dto = gson.fromJson(json, RiskOptionsDto::class.java)
        val savedEvent = gson.fromJson("""{"id":42,"title":"시험","start_at":"2026-10-20T09:00:00+09:00",
            "alarm_plan":{"status":"ok","tau_used":0.97,"alarm_at":"2026-10-20T07:20:00+09:00"}}""",
            EventDto::class.java)
        val api = proxy(EventsApi::class.java) { method, args ->
            calls += method
            assertEquals(42L, args[0])
            when (method) {
                "riskOptions" -> Response.success(dto)
                "update" -> {
                    assertEquals(.97, (args[1] as EventUpdateRequest).tauOverride!!, .00001)
                    assertNull((args[1] as EventUpdateRequest).alarmEnabled)
                    Response.success(savedEvent)
                }
                else -> error("Unexpected API call $method")
            }
        }
        val profile = proxy(ProfileApi::class.java) { name, _ -> error("Unexpected profile $name") }
        val repository = EventRepository(api = api, profileApi = profile)
        val loaded = repository.loadRiskOptions(42) as EventRepository.Result.Success<RiskOptionsView>
        assertEquals(listOf("riskOptions"), calls)
        val chosen = loaded.data.option("safe")!!
        val saved = repository.saveRiskOption(42, chosen.tau) as EventRepository.Result.Success<AlarmPlanView>
        assertEquals(.97, saved.data.tauUsed!!, .00001)
        assertEquals(listOf("riskOptions", "update"), calls)
        assertTrue(gson.toJson(EventUpdateRequest(tauOverride = chosen.tau)).contains("\"tau_override\":0.97"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
