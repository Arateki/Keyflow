package com.keyflow.net

import com.keyflow.BuildConfig
import com.keyflow.data.models.Ipv4FirstDns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Cliente HTTP comum do app: IPv4 primeiro, limite para a chamada inteira e o token do Access num ponto só. */
object KeyFlowHttpClient {
    fun base(): OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(
            CloudflareAccessInterceptor(
                clientId = BuildConfig.KEYFLOW_STAGING_ACCESS_CLIENT_ID,
                clientSecret = BuildConfig.KEYFLOW_STAGING_ACCESS_CLIENT_SECRET,
                apiBaseUrl = BuildConfig.KEYFLOW_API_BASE_URL,
            ),
        )
        .build()
}
