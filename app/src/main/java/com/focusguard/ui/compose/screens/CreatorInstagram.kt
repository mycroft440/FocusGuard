package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun openCreatorInstagram(context: Context) {
    val profileUri = Uri.parse(CREATOR_INSTAGRAM_PROFILE_URL)
    val instagramIntent = Intent(Intent.ACTION_VIEW, profileUri).apply {
        setPackage(INSTAGRAM_PACKAGE_NAME)
    }
    val openedInInstagram = runCatching {
        context.startActivity(instagramIntent)
    }.isSuccess

    if (!openedInInstagram) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, profileUri))
        }
    }
}

internal const val CREATOR_INSTAGRAM_PROFILE_URL =
    "https://www.instagram.com/jose_gustavo55/"
internal const val INSTAGRAM_PACKAGE_NAME = "com.instagram.android"
