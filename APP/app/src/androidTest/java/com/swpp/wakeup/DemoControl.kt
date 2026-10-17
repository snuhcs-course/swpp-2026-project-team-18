package com.swpp.wakeup

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals

internal fun demoControl(config: JSONObject, path: String): JSONObject {
    val connection = URL(config.getString("control_url") + path).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 90_000
    connection.setRequestProperty("X-QA-Token", config.getString("control_token"))
    try {
        val code = connection.responseCode
        val body = (if (code == 200) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        assertEquals("QA 제어 API $path: $body", 200, code)
        return JSONObject(body)
    } finally {
        connection.disconnect()
    }
}
