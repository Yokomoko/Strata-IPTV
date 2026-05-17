package com.strata.tv.testing

import android.content.Context
import androidx.room.Room
import com.strata.tv.data.db.AppDatabase
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.FavouriteDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.WatchHistoryDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.di.AppModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the production [AppModule]'s database binding with an
 * in-memory Room database for tests.  Each test that uses Hilt gets a
 * fresh DB because [HiltAndroidRule] re-builds the component per test.
 *
 * DAOs are re-provided here because Hilt's `@TestInstallIn` replaces
 * the entire module, not just one binding.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class],
)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // Tests synchronously seed data; safe.
            .build()

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

    @Provides
    @Singleton
    fun provideHttpClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideM3uParser(): com.strata.tv.data.m3u.M3uParser =
        com.strata.tv.data.m3u.M3uParser()
}
