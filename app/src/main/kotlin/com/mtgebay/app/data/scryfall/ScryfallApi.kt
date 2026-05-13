package com.mtgebay.app.data.scryfall

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Read-only Retrofit client for the Scryfall REST API.
 *
 * Scryfall asks every consumer to:
 *   1. Set a [User-Agent](https://scryfall.com/docs/api) identifying the app.
 *   2. Stay under ~10 requests/second; one request per 50–100 ms is fine.
 *
 * We honor (1) with a static interceptor and (2) by debouncing on the UI side.
 * No auth is required.
 */
interface ScryfallApi {

    /**
     * Returns a paginated list of cards matching the Scryfall search query
     * [q] (using their syntax — e.g. `!"Lightning Bolt"` for an exact match).
     *
     * For our manual-search flow we pass `unique=prints&order=released` so the
     * user sees one entry per distinct printing, newest first.
     */
    @GET("cards/search")
    suspend fun searchCards(
        @Query("q") q: String,
        @Query("unique") unique: String = "prints",
        @Query("order") order: String = "released",
        @Query("dir") dir: String = "desc",
    ): ScryfallList

    /** Lightweight name-only autocomplete. Returns up to ~20 names. */
    @GET("cards/autocomplete")
    suspend fun autocomplete(
        @Query("q") q: String,
    ): ScryfallAutocompleteResponse

    /** Fetch a single card by its Scryfall UUID. */
    @GET("cards/{id}")
    suspend fun cardById(@Path("id") id: String): ScryfallCard

    companion object {
        const val BASE_URL = "https://api.scryfall.com/"
        const val USER_AGENT = "mtg-ebay/0.1 (+https://github.com/jared0108/mtg-ebay-secret-move)"

        /**
         * Build a [ScryfallApi]. Tests inject a custom [baseUrl] (MockWebServer)
         * and may swap the OkHttp client; production callers can use the
         * zero-arg overload.
         */
        fun create(
            baseUrl: String = BASE_URL,
            okHttpClient: OkHttpClient = defaultOkHttpClient(),
        ): ScryfallApi {
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
                .create(ScryfallApi::class.java)
        }

        fun defaultOkHttpClient(loggingLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE): OkHttpClient {
            val userAgent = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
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
