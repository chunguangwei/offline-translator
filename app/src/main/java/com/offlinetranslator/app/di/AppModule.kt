package com.offlinetranslator.app.di

import android.content.Context
import androidx.room.Room
import com.offlinetranslator.app.core.data.db.AppDatabase
import com.offlinetranslator.app.core.data.db.ChatDao
import com.offlinetranslator.app.core.data.db.MIGRATION_1_2
import com.offlinetranslator.app.core.data.db.MIGRATION_2_3
import com.offlinetranslator.app.core.data.db.TranslationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "offline_translator.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideTranslationDao(db: AppDatabase): TranslationDao = db.translationDao()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // model files are big
        .retryOnConnectionFailure(true)
        .eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) {
                android.util.Log.i("OT-Net", "→ ${call.request().method} ${call.request().url}")
            }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                android.util.Log.e("OT-Net", "✗ ${call.request().url}", ioe)
            }
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                android.util.Log.i("OT-Net", "← ${response.code} ${call.request().url}")
            }
        })
        .build()
}
