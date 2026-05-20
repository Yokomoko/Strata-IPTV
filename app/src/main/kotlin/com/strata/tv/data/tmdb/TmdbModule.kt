package com.strata.tv.data.tmdb

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Hilt bindings for the TMDB Retrofit client.
 *
 * Reuses the shared OkHttp from [com.strata.tv.di.AppModule] so DNS,
 * connection pool and TLS sessions are shared across IPTV +
 * enrichment calls.
 */
@Module
@InstallIn(SingletonComponent::class)
object TmdbModule {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideTmdbApi(http: OkHttpClient): TmdbApi {
        // Branch a TMDB-specific OkHttp off the shared client so we
        // share DNS / connection pool / TLS sessions but can attach a
        // TMDB-only interceptor.
        //
        // `Accept-Encoding: identity` disables OkHttp's transparent
        // gzip decompression for this client.  Without it, ~30-40% of
        // TMDB responses fail with "gzip finished without exhausting
        // source" — a known interaction between OkHttp 4.x's HTTP/2
        // gzip path and Retrofit's kotlinx-serialization converter
        // when R8 is enabled (the converter reads the JSON body but
        // doesn't drain trailing whitespace, and GzipSource's
        // finish-check then trips).
        //
        // TMDB responses are small (1-5 KB), so paying the
        // uncompressed bandwidth cost is well worth getting 100% of
        // posters / plots through.  The shared HTTP client still uses
        // gzip for the 150 MB EPG fetch where it matters.
        val tmdbHttp = http.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build()
                chain.proceed(req)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(tmdbHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }
}
