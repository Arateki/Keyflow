package com.keyflow.data.models

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Cliente HTTP comum do app: IPv4 primeiro e limite para a chamada inteira. */
object KeyFlowHttpClient {
    fun base(): OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
}
