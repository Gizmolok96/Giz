package com.footballanalyzer.app.data.api

import com.footballanalyzer.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
        else HttpLoggingInterceptor.Level.NONE
    }

    private val apiKeyInterceptor = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val url = original.url.newBuilder()
            .addQueryParameter("apikey", BuildConfig.SSTATS_API_KEY)
            .build()
        chain.proceed(original.newBuilder().url(url).build())
    }

    /**
     * Automatically retries on HTTP 429 (Too Many Requests).
     * Waits for the delay from the Retry-After header if present,
     * otherwise backs off: 2s → 4s → 8s. Max 3 retries.
     */
    private val retryInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        var response = chain.proceed(request)
        var retries = 0
        while (response.code == 429 && retries < 3) {
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: (2L shl retries)
            response.close()
            Thread.sleep(retryAfterSec * 1000)
            response = chain.proceed(request)
            retries++
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(apiKeyInterceptor)
        .addInterceptor(retryInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val footballApi: FootballApi by lazy {
        Retrofit.Builder()
            .baseUrl("${BuildConfig.SSTATS_BASE_URL}/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FootballApi::class.java)
    }
}
