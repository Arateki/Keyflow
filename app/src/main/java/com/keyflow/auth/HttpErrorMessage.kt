package com.keyflow.auth

import android.util.Log
import com.keyflow.BuildConfig
import org.json.JSONObject

/** Mensagens HTTP curtas: detalhes do corpo só ficam disponíveis em builds de desenvolvimento. */
object HttpErrorMessage {
    fun of(service: String, status: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = listOf(
            json?.optJSONObject("error")?.optString("code"),
            json?.optJSONObject("error")?.optString("type"),
            json?.optString("code"),
            json?.optString("status"),
        ).firstOrNull { it.isSafeCode() }
        val suffix = code?.let { " $it" }.orEmpty()
        val debugDetail = if (BuildConfig.DEBUG) {
            listOf(
                json?.optJSONObject("error")?.optString("message"),
                json?.optString("detail"),
            ).firstOrNull { !it.isNullOrBlank() }?.take(MAX_DEBUG_DETAIL)
        } else {
            null
        }
        return buildString {
            append(service)
            append(" HTTP ")
            append(status)
            append(suffix)
            if (!debugDetail.isNullOrBlank()) append(": ").append(debugDetail)
        }
    }

    fun log(tag: String, status: Int, body: String) {
        if (BuildConfig.DEBUG) Log.e(tag, "HTTP $status body=$body") else Log.e(tag, "HTTP $status")
    }

    private fun String?.isSafeCode(): Boolean = !isNullOrBlank() &&
        length <= MAX_CODE && matches(SAFE_CODE)

    private const val MAX_CODE = 64
    private const val MAX_DEBUG_DETAIL = 240
    private val SAFE_CODE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
}
