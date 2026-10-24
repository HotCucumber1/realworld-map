package com.worldexplorer.app.core.map

import android.content.Context
import com.worldexplorer.app.BuildConfig
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/**
 * One-time MapLibre setup. Tiles are requested with an identifying User-Agent, as required by the
 * OpenStreetMap tile usage policy; MapLibre's own disk cache keeps recently seen tiles for offline use.
 */
object MapEnvironment {
    val USER_AGENT = "WorldExplorer/${BuildConfig.VERSION_NAME} (Android)"

    fun init(context: Context) {
        MapLibre.getInstance(context)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }
}
