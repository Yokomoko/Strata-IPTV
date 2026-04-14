package com.strata.tv.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the bits of TMDB v3 we use.
 *
 * Search endpoints locate a title; detail endpoints pull credits,
 * certifications and backdrops for the detail screen (Phase 9).
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

    /** Movie detail with credits + US release dates (for certification). */
    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,release_dates,watch/providers,videos",
    ): TmdbMovieDetail

    /** TV detail with credits + content ratings + watch providers. */
    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,content_ratings,watch/providers",
    ): TmdbTvDetail

    /** Season detail — episode names and overviews for a single season. */
    @GET("tv/{id}/season/{season}")
    suspend fun tvSeason(
        @Path("id") id: Int,
        @Path("season") season: Int,
        @Query("api_key") apiKey: String,
    ): TmdbSeasonDetail
}

// ---------------------------------------------------------------------------
// Search response models (existing)
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Movie detail model (append_to_response=credits,release_dates)
// ---------------------------------------------------------------------------

@Serializable
data class TmdbMovieDetail(
    val id: Int,
    val title: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDatesWrapper? = null,
    @SerialName("watch/providers") val watchProviders: TmdbWatchProvidersWrapper? = null,
    val videos: TmdbVideosWrapper? = null,
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
)

@Serializable
data class TmdbCastMember(
    val name: String,
    val order: Int = 0,
)

@Serializable
data class TmdbReleaseDatesWrapper(
    val results: List<TmdbReleaseDateCountry> = emptyList(),
)

@Serializable
data class TmdbReleaseDateCountry(
    @SerialName("iso_3166_1") val iso: String,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateEntry> = emptyList(),
)

@Serializable
data class TmdbReleaseDateEntry(
    val certification: String = "",
)

// ---------------------------------------------------------------------------
// TV detail model (append_to_response=credits,content_ratings)
// ---------------------------------------------------------------------------

@Serializable
data class TmdbTvDetail(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatingsWrapper? = null,
    @SerialName("watch/providers") val watchProviders: TmdbWatchProvidersWrapper? = null,
)

@Serializable
data class TmdbContentRatingsWrapper(
    val results: List<TmdbContentRating> = emptyList(),
)

@Serializable
data class TmdbContentRating(
    @SerialName("iso_3166_1") val iso: String,
    val rating: String = "",
)

// ---------------------------------------------------------------------------
// Videos (append_to_response=videos)
// ---------------------------------------------------------------------------

@Serializable
data class TmdbVideosWrapper(
    val results: List<TmdbVideo> = emptyList(),
)

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val official: Boolean = false,
)

// ---------------------------------------------------------------------------
// Watch providers (append_to_response=watch/providers)
// ---------------------------------------------------------------------------

@Serializable
data class TmdbWatchProvidersWrapper(
    val results: Map<String, TmdbCountryProviders> = emptyMap(),
)

@Serializable
data class TmdbCountryProviders(
    val flatrate: List<TmdbProvider> = emptyList(),
)

@Serializable
data class TmdbProvider(
    @SerialName("provider_name") val providerName: String,
    @SerialName("provider_id") val providerId: Int,
)

// ---------------------------------------------------------------------------
// Season detail (/tv/{id}/season/{n}) — episode names
// ---------------------------------------------------------------------------

@Serializable
data class TmdbSeasonDetail(
    val episodes: List<TmdbEpisodeDetail> = emptyList(),
)

@Serializable
data class TmdbEpisodeDetail(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    val overview: String = "",
)
