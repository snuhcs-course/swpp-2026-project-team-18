package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class UpcomingConfirmationTest {
    @Test fun confirmationMetadataPreservesUnknownAndBothServerStates() {
        listOf("null" to null, "true" to true, "false" to false).forEach { (json, expected) ->
            val event = Gson().fromJson("""{
                "id":1,"title":"수업","start_at":"2026-12-07T09:00:00+09:00",
                "source":"manual","alarm_plan":{"status":"ok","alarm_confirmed":$json}
            }""", EventDto::class.java)
            assertEquals(expected, event.toUpcoming(ZoneId.of("Asia/Seoul"))?.alarmConfirmed)
        }
    }
}
