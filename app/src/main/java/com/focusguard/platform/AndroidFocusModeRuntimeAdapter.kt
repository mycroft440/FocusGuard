package com.focusguard.platform

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.focusguard.domain.port.FocusModeRuntimePort
import com.focusguard.receiver.FocusModeReceiver
import com.focusguard.service.FocusModeForegroundService
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.utils.PermissionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidFocusModeRuntimeAdapter @Inject constructor(
    @ApplicationContext private val context: Context
) : FocusModeRuntimePort {

    override fun isAccessibilityEnabled(): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context)

    override fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    override fun activate(endTimeMillis: Long) {
        FocusModeForegroundService.start(context)
        FocusModeReceiver.scheduleExpiration(context, endTimeMillis)
        FocusModeNotificationService.requestRefresh(context)
    }

    override fun deactivate() {
        FocusModeReceiver.cancelExpiration(context)
        FocusModeForegroundService.stop(context)
        FocusModeNotificationService.requestRefresh(context)
    }
}
