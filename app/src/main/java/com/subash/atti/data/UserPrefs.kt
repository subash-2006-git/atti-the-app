package com.subash.atti.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** Stores the user's display name on the phone. */
class UserPrefs(private val context: Context) {

    /** The saved name, or null if the user hasn't picked one yet. */
    val name: Flow<String?> = context.dataStore.data.map { it[KEY_NAME] }

    suspend fun saveName(name: String) {
        context.dataStore.edit { it[KEY_NAME] = NameRules.clean(name) }
    }

    private companion object {
        val KEY_NAME = stringPreferencesKey("name")
    }
}

object NameRules {
    const val MAX_LENGTH = 20

    /** Trims and collapses runs of whitespace, so "  Sub   ash " becomes "Sub ash". */
    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun isValid(raw: String): Boolean = clean(raw).length in 1..MAX_LENGTH
}
