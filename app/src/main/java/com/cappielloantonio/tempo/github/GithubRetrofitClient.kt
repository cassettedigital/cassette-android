package com.cappielloantonio.tempo.github

import com.cappielloantonio.tempo.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GithubRetrofitClient(github: Github) {
    var retrofit: Retrofit

    init {
        retrofit = Retrofit.Builder()
                .baseUrl(github.url)
                .addConverterFactory(GsonConverterFactory.create())
                .client(getOkHttpClient())
                .build()
    }

    private fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
                .addInterceptor(getHttpLoggingInterceptor())
                .build()
    }

    private fun getHttpLoggingInterceptor(): HttpLoggingInterceptor {
        val loggingInterceptor = HttpLoggingInterceptor()
        // cassette: debug-only BODY logging, matching the Subsonic client — keep
        // request/response bodies out of release logcat.
        loggingInterceptor.setLevel(
                if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        )
        return loggingInterceptor
    }
}