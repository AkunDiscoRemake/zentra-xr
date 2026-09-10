package com.zentra.xr

import android.app.Application
import android.os.StrictMode

/**
 * ZENTRA XR Beta 1 - Application
 * Privacy-first: no analytics, no tracking, no remote logging
 */
class ZentraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
