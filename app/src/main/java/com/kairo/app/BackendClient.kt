package com.kairo.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BackendClient {

    fun chat(base: String, token: String, msgs: List<Pair<String, String>>): Pair<Boolean, String> {
        val arr = JSONArray()
        for (m in msgs) {
            arr.put(JSONObject().put("role", m.first).put("text", m.second))
        }
        val body = JSONObject().put("messages", arr)

        val conn = URL(base.trimEnd('/') + "/chat").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("x-kairo-token", token)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

        if (code == 200) {
            return Pair(true, JSONObject(raw).getString("reply"))
        }
        if (code == 401) {
            return Pair(false, "Token galat hai. Upar \u2699 dabake sahi KAIRO_TOKEN daalo.")
        }
        val detail = try {
            JSONObject(raw).optString("detail", "")
        } catch (e: Exception) {
            ""
        }
        return Pair(false, "Server error " + code + ": " + detail.take(200))
    }
}
