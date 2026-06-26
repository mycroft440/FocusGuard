package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay

@AndroidEntryPoint
class BlockNoticeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            if (intent.getBooleanExtra("STRICT_BLOCK", false)) {
                // Bloqueado
            } else {
                finish()
            }
        }
        setContent {
            FocusGuardTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBg.copy(alpha = 0.95f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            color = AccentCyan.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.size(100.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, AccentCyan)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_shield),
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = AccentCyan
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        Text(
                            text = "App bloqueado pelo\nFocusGuard",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center,
                            lineHeight = 32.sp
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Mantenha o foco em seus objetivos.",
                            fontSize = 16.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    delay(2000)
                    if (intent.getBooleanExtra("STRICT_BLOCK", false)) {
                        // No modo rigoroso: redirecionar para tela de bloqueio, não para home
                        val lockIntent = Intent(this@BlockNoticeActivity, PomodoroLockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(lockIntent)
                        finish()
                    } else {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        finish()
                    }
                }
            }
        }
    }
}

