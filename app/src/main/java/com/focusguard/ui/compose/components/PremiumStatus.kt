package com.focusguard.ui.compose.components

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.focusguard.monetization.PremiumStateStore

@Composable
fun rememberPremiumStatus(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { mutableStateOf(PremiumStateStore.isPremium(context)) }
    DisposableEffect(context) {
        val preferences = PremiumStateStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = PremiumStateStore.isPremium(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state.value = PremiumStateStore.isPremium(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
