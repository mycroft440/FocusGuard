package com.focusguard.pomodoro

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object PomodoroAlarmController {
    data class AlarmSound(
        val id: Int,
        val name: String,
        internal val pattern: List<ToneStep>
    )

    internal data class ToneStep(
        val tone: Int,
        val durationMs: Int,
        val pauseMs: Long
    )

    val sounds: List<AlarmSound> = listOf(
        AlarmSound(0, "Clássico", listOf(ToneStep(ToneGenerator.TONE_PROP_BEEP, 280, 160))),
        AlarmSound(1, "Duplo", listOf(
            ToneStep(ToneGenerator.TONE_PROP_BEEP2, 180, 90),
            ToneStep(ToneGenerator.TONE_PROP_BEEP2, 180, 220)
        )),
        AlarmSound(2, "Confirmação", listOf(ToneStep(ToneGenerator.TONE_PROP_ACK, 320, 180))),
        AlarmSound(3, "Profundo", listOf(ToneStep(ToneGenerator.TONE_PROP_NACK, 360, 220))),
        AlarmSound(4, "Suave", listOf(ToneStep(ToneGenerator.TONE_PROP_PROMPT, 250, 210))),
        AlarmSound(5, "Campainha", listOf(ToneStep(ToneGenerator.TONE_SUP_RINGTONE, 420, 180))),
        AlarmSound(6, "Pulso 1", listOf(
            ToneStep(ToneGenerator.TONE_DTMF_1, 150, 80),
            ToneStep(ToneGenerator.TONE_DTMF_3, 150, 220)
        )),
        AlarmSound(7, "Pulso 2", listOf(
            ToneStep(ToneGenerator.TONE_DTMF_3, 170, 70),
            ToneStep(ToneGenerator.TONE_DTMF_6, 170, 220)
        )),
        AlarmSound(8, "Pulso 3", listOf(
            ToneStep(ToneGenerator.TONE_DTMF_6, 170, 70),
            ToneStep(ToneGenerator.TONE_DTMF_9, 170, 220)
        )),
        AlarmSound(9, "Escada", listOf(
            ToneStep(ToneGenerator.TONE_DTMF_1, 120, 60),
            ToneStep(ToneGenerator.TONE_DTMF_3, 120, 60),
            ToneStep(ToneGenerator.TONE_DTMF_6, 120, 60),
            ToneStep(ToneGenerator.TONE_DTMF_9, 180, 220)
        ))
    )

    fun soundName(index: Int): String = sounds.getOrElse(index) { sounds.first() }.name

    suspend fun preview(context: Context, soundIndex: Int) {
        playPattern(
            context = context,
            soundIndex = soundIndex,
            durationMillis = 1_500L,
            vibrationEnabled = false
        )
    }

    suspend fun play(context: Context, config: PomodoroPlanConfig) {
        if (!config.soundEnabled && !config.vibrationEnabled) return
        playPattern(
            context = context,
            soundIndex = config.soundIndex,
            durationMillis = config.alarmDurationSeconds.coerceIn(1, 60) * 1_000L,
            vibrationEnabled = config.vibrationEnabled,
            soundEnabled = config.soundEnabled
        )
    }

    private suspend fun playPattern(
        context: Context,
        soundIndex: Int,
        durationMillis: Long,
        vibrationEnabled: Boolean,
        soundEnabled: Boolean = true
    ) {
        val sound = sounds.getOrElse(soundIndex) { sounds.first() }
        val toneGenerator = if (soundEnabled) {
            runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
        } else {
            null
        }
        val vibrator = if (vibrationEnabled) {
            runCatching { context.getSystemService(Vibrator::class.java) }.getOrNull()
        } else {
            null
        }

        if (vibrator?.hasVibrator() == true) {
            runCatching {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 300L, 180L, 300L, 350L),
                        1
                    )
                )
            }
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + durationMillis
        try {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                sound.pattern.forEach { step ->
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) return@forEach
                    toneGenerator?.startTone(step.tone, step.durationMs)
                    delay(step.durationMs.toLong())
                    toneGenerator?.stopTone()
                    if (step.pauseMs > 0L) delay(step.pauseMs)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            runCatching { toneGenerator?.stopTone() }
            runCatching { toneGenerator?.release() }
            runCatching { vibrator?.cancel() }
        }
    }
}
