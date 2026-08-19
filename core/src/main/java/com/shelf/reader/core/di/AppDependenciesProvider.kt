package com.shelf.reader.core.di

import android.content.Context
import com.shelf.reader.core.gamification.ReadingTrackerFacade

interface AppDependenciesProvider {
    val appContext: Context
    val readingTracker: ReadingTrackerFacade
}
