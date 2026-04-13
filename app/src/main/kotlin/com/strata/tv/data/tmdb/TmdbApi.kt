package com.strata.tv.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the bits of TMDB v3 we use.
 *
 * Keeping the surface tight — just movie + tv search, plus the
 * `/movie/{id}/watch/providers` endpoint for the home screen's
 * provider rails (will land in a later commit).
 *
 * All endpoints take the API key as a query parameter.  We could put
 * it in an OkHttp interceptor but there's only ever one TMDB
 * subscriber so explicit is fine.
 */
interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("year") year: Int? = null,
        @Query("include_adult") includeAdult: Boolean = false,
    ): TmdbMovieSearchResponse

    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("include_adult") includeAdult: Boolean = false,
    ): TmdbTvSearchResponse
}

@Serializable
data class TmdbMovieSearchResponse(
    val results: List<TmdbMovie> = emptyList(),
)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
data class TmdbTvSearchResponse(
    val results: List<TmdbTv> = emptyList(),
)

@Serializable
data class TmdbTv(
    val id: Int,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)
