package com.focusguard

import android.app.Application
import com.focusguard.utils.FocusGuardLogger

class FocusGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FocusGuardLogger.init(this)
    }
}

