package com.shelf.reader.core.di

import android.content.Context

/**
 * Marker interface implemented by [android.app.Application] so feature modules
 * (e.g. `:library`, `:reader`, `:player`, `:ftp`) can resolve dependency graph
 * roots without a compile-time dependency on `:app`.
 *
 * Retrieve anywhere you have a Context:
 * ```
 * val provider = context.applicationContext as AppDependenciesProvider
 * ```
 */
interface AppDependenciesProvider {
    val appContext: Context
}
