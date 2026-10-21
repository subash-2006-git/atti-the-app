package com.subash.atti

import android.app.Application
import com.subash.atti.data.UserPrefs

/** Holds the app-wide objects. ATTI is small enough not to need a DI library. */
class AttiApplication : Application() {
    lateinit var userPrefs: UserPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        userPrefs = UserPrefs(this)
    }
}
