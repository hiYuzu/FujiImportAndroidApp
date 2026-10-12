package com.fujiimport.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferences by preferencesDataStore(name = "gallery")

class UserPreferences(private val context: Context) {
    private val ascendingKey = booleanPreferencesKey("ascending")
    val ascending = context.preferences.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[ascendingKey] ?: true }

    suspend fun setAscending(value: Boolean) { context.preferences.edit { it[ascendingKey] = value } }
}
