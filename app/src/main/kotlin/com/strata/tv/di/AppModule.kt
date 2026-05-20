package com.strata.tv.di

import android.content.Context
import androidx.room.Room
import com.strata.tv.data.db.AppDatabase
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.FavouriteDao
import com.strata.tv.data.db.MIGRATION_3_4
import com.strata.tv.data.db.MIGRATION_4_5
import com.strata.tv.data.db.MIGRATION_5_6
import com.strata.tv.data.db.MIGRATION_6_7
import com.strata.tv.data.db.MIGRATION_7_8
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.WatchHistoryDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.m3u.M3uParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt's bindings for the data layer.
 *
 * Provides Room, OkHttp + the M3U parser as singletons.  Each DAO is
 * exposed as its own `@Provides` so a screen's ViewModel can inject
 * exactly the surface it needs without dragging the whole AppDatabase
 * along with it.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context = context,
            klass = AppDatabase::class.java,
            name = "strata-tv.db",
        )
            // Default WAL mode is fine — large EPG writes don't block
            // concurrent reads from the home screen.
            //
            // Real migrations for every schema bump so user data —
            // favourites, watchlist, continue-watching, last-watched
            // channel — survives app updates.  Destructive fallback is
            // a last-resort safety net, not the happy path.
            .addMigrations(
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun sourceDao(db: AppDatabase): SourceDao = db.sourceDao()
    @Provides fun contentDao(db: AppDatabase): ContentDao = db.contentDao()
    @Provides fun channelDao(db: AppDatabase): ChannelDao = db.channelDao()
    @Provides fun movieDao(db: AppDatabase): MovieDao = db.movieDao()
    @Provides fun seriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
    @Provides fun episodeDao(db: AppDatabase): EpisodeDao = db.episodeDao()
    @Provides fun programmeDao(db: AppDatabase): ProgrammeDao = db.programmeDao()
    @Provides fun continueWatchingDao(db: AppDatabase): ContinueWatchingDao =
        db.continueWatchingDao()
    @Provides fun watchHistoryDao(db: AppDatabase): WatchHistoryDao = db.watchHistoryDao()
    @Provides fun favouriteDao(db: AppDatabase): FavouriteDao = db.favouriteDao()
    @Provides fun watchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()

    /**
     * Shared OkHttp client.  IPTV providers + TMDB calls share this
     * so connection pooling + DNS cache is amortised.  Tight
     * connect timeout (10s) so we fail fast when the user's WiFi is
     * broken instead of hanging the UI.
     */
    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // EPG XMLTV bodies can be ~150 MB.
        .build()

    @Provides
    @Singleton
    fun provideM3uParser(): M3uParser = M3uParser()
}
