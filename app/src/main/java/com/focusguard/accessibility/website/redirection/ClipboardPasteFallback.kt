package com.focusguard.accessibility.website.redirection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Certified paste fallback used only after ACTION_SET_TEXT fails.
 *
 * FocusGuard never leaves the redirect URL in the user's clipboard: the previous
 * clip is restored, or an originally empty clipboard is cleared on API 28+.
 * On API 26-27 an empty clipboard cannot be restored exactly, so paste is skipped.
 */
internal object ClipboardPasteFallback {
    fun pasteSafely(
        context: Context,
        text: String,
        pasteAction: () -> AddressBarRedirectionActions.Result
    ): AddressBarRedirectionActions.Result {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return AddressBarRedirectionActions.Result(
                AddressBarRedirectionActions.Status.NOT_FOUND
            )

        val previous = runCatching { clipboard.primaryClip }
        if (previous.isFailure) {
            return AddressBarRedirectionActions.Result(
                AddressBarRedirectionActions.Status.REJECTED
            )
        }
        val previousClip = previous.getOrNull()
        if (previousClip == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return AddressBarRedirectionActions.Result(
                AddressBarRedirectionActions.Status.NOT_FOUND
            )
        }

        val prepared = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("FocusGuard redirect", text))
        }.isSuccess
        if (!prepared) {
            return AddressBarRedirectionActions.Result(
                AddressBarRedirectionActions.Status.REJECTED
            )
        }

        return try {
            pasteAction()
        } finally {
            runCatching {
                if (previousClip != null) {
                    clipboard.setPrimaryClip(previousClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                }
            }
        }
    }
}
