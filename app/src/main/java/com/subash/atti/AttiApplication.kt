package com.subash.atti

import android.app.Application
import com.subash.atti.data.NearbyManager
import com.subash.atti.data.UserPrefs
import kotlinx.coroutines.MainScope

/** Holds the app-wide objects. ATTI is small enough not to need a DI library. */
class AttiApplication : Application() {
    lateinit var userPrefs: UserPrefs
        private set
    lateinit var nearby: NearbyManager
        private set

    override fun onCreate() {
        super.onCreate()
        userPrefs = UserPrefs(this)
        nearby = NearbyManager(this, MainScope())
    }
}
