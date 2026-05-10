package com.focusguard.ui.compose.screens

import androidx.compose.runtime.*
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.utils.PermissionUtils

@Composable
fun InsightsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val usageAccessGranted = remember { mutableStateOf(PermissionUtils.isUsageAccessEnabled(context)) }

    // Atualiza permissão de uso ao voltar da tela de settings
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                usageAccessGranted.value = PermissionUtils.isUsageAccessEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (!usageAccessGranted.value) {
            Text("Permissão necessária para visualizar Insights", color = TextPrimary)
            // botão para abrir configurações continua aqui...
        } else {
            // Conteúdo do Insights normalmente aqui
        }
    }
}