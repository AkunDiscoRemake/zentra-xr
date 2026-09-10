package com.zentra.xr

import android.app.Application
import android.os.StrictMode
import android.util.Log

/**
 * ZENTRA XR Beta 1 - Application
 * Privacy-first: no analytics, no tracking, no remote logging
 */
class ZentraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug check without BuildConfig (buildConfig feature disabled for simplicity)
        // In debug builds, enable StrictMode for catching issues
        try {
            val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build()
                )
                Log.i("ZentraApp", "StrictMode enabled (debuggable build)")
            }
        } catch (e: Exception) {
            Log.w("ZentraApp", "Could not set StrictMode", e)
        }
    }
}
