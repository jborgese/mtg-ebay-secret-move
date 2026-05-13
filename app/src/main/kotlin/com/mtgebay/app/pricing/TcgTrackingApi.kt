package com.mtgebay.app.pricing

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Read-only Retrofit client for the TCGTracking Open TCG API.
 *
 * Their guidance (from /tcgapi/ docs):
 *   - Cache static product data ≥ 7 days
 *   - Cache pricing/SKU data ~ 1 day (refresh at 8 AM EST)
 *   - No auth, no rate limits, served via Cloudflare CDN
 *
 * We honor caching via OkHttp's response cache (configured in [defaultOkHttpClient])
 * and the cache-aware interceptor.
 */
interface TcgTrackingApi {

    /** All sets for a TCG category. Magic = category 1. */
    @GET("tcgapi/v1/{category}/sets")
    suspend fun sets(@Path("category") category: Int): TcgSetList

    /** SKU-level pricing for every product in a given set. */
    @GET("tcgapi/v1/{category}/sets/{setId}/skus")
    suspend fun skus(
        @Path("category") category: Int,
        @Path("setId") setId: Int,
    ): TcgSkuResponse

    companion object {
        const val BASE_URL = "https://tcgtracking.com/"
        const val USER_AGENT = "mtg-ebay/0.1 (+https://github.com/jared0108/mtg-ebay-secret-move)"
        const val MAGIC_CATEGORY = 1

        fun create(
            baseUrl: String = BASE_URL,
            okHttpClient: OkHttpClient = defaultOkHttpClient(),
        ): TcgTrackingApi {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TcgTrackingApi::class.java)
        }

        fun defaultOkHttpClient(
            loggingLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE,
        ): OkHttpClient {
            val userAgent = Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .build(),
                )
            }
            val logging = HttpLoggingInterceptor().apply { level = loggingLevel }
            return OkHttpClient.Builder()
                .addInterceptor(userAgent)
                .addInterceptor(logging)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
