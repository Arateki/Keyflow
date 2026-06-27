package com.keyflow.data

import com.keyflow.core.RecordingMode

data class Config(
    val endpointUrl: String = "",
    val authHeaderName: String = "Authorization",
    val authHeaderValue: String = "",
    val model: String = "whisper-1",
    val language: String? = null,
    val recordingMode: RecordingMode = RecordingMode.TOGGLE,
) {
    fun isComplete(): Boolean = endpointUrl.isNotBlank() && authHeaderValue.isNotBlank()
}
