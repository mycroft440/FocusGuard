package com.focusguard.pomodoro

import android.content.Context
import com.focusguard.R
import com.focusguard.utils.SecurePrefsManager
import com.focusguard.widget.PomodoroWidgetProvider
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuração completa de um ciclo Pomodoro.
 *
 * targetSessions == 0 significa "até eu parar".
 */
data class PomodoroPlanConfig(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longBreakEvery: Int = 4,
    val targetSessions: Int = 0,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val alarmDurationSeconds: Int = 5,
    val soundIndex: Int = 0,
    val silenceNotifications: Boolean = false,
    val hideNotifications: Boolean = false,
    val strictBlocking: Boolean = false
) {
    fun normalized(): PomodoroPlanConfig = copy(
        focusMinutes = focusMinutes.coerceIn(1, 24 * 60),
        shortBreakMinutes = shortBreakMinutes.coerceIn(1, 120),
        longBreakMinutes = longBreakMinutes.coerceIn(1, 12 * 60),
        longBreakEvery = longBreakEvery.coerceIn(1, 100),
        targetSessions = targetSessions.coerceIn(0, 100),
        alarmDurationSeconds = alarmDurationSeconds.coerceIn(1, 60),
        soundIndex = soundIndex.coerceIn(0, 9)
    )
}

enum class PomodoroPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK
}

data class PomodoroCycleRuntime(
    val active: Boolean,
    val phase: PomodoroPhase,
    val completedFocusSessions: Int,
    val config: PomodoroPlanConfig,
    val intervalEndTime: Long,
    val intervalDurationMillis: Long
)

data class PomodoroProfile(
    val id: String,
    val name: String,
    val config: PomodoroPlanConfig,
    val builtIn: Boolean = false
)

class PomodoroPlanStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = SecurePrefsManager(appContext).prefs

    fun loadConfig(): PomodoroPlanConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return DEFAULT_CONFIG
        return runCatching { configFromJson(JSONObject(raw)) }
            .getOrDefault(DEFAULT_CONFIG)
            .normalized()
    }

    fun saveConfig(config: PomodoroPlanConfig): PomodoroPlanConfig {
        val normalized = config.normalized()
        prefs.edit().putString(KEY_CONFIG, configToJson(normalized).toString()).commit()
        refreshWidgets()
        return normalized
    }

    fun readRuntime(): PomodoroCycleRuntime? {
        val raw = prefs.getString(KEY_RUNTIME, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PomodoroCycleRuntime(
                active = json.optBoolean("active", false),
                phase = runCatching {
                    PomodoroPhase.valueOf(json.optString("phase", PomodoroPhase.FOCUS.name))
                }.getOrDefault(PomodoroPhase.FOCUS),
                completedFocusSessions = json.optInt("completedFocusSessions", 0)
                    .coerceAtLeast(0),
                config = configFromJson(json.getJSONObject("config")).normalized(),
                intervalEndTime = json.optLong("intervalEndTime", 0L),
                intervalDurationMillis = json.optLong("intervalDurationMillis", 0L)
                    .coerceAtLeast(0L)
            )
        }.getOrNull()
    }

    fun beginRuntime(config: PomodoroPlanConfig): PomodoroCycleRuntime {
        val runtime = PomodoroCycleRuntime(
            active = true,
            phase = PomodoroPhase.FOCUS,
            completedFocusSessions = 0,
            config = config.normalized(),
            intervalEndTime = 0L,
            intervalDurationMillis = 0L
        )
        saveRuntime(runtime)
        return runtime
    }

    fun saveRuntime(runtime: PomodoroCycleRuntime) {
        val json = JSONObject()
            .put("active", runtime.active)
            .put("phase", runtime.phase.name)
            .put("completedFocusSessions", runtime.completedFocusSessions)
            .put("config", configToJson(runtime.config.normalized()))
            .put("intervalEndTime", runtime.intervalEndTime)
            .put("intervalDurationMillis", runtime.intervalDurationMillis)
        prefs.edit().putString(KEY_RUNTIME, json.toString()).commit()
        refreshWidgets()
    }

    fun clearRuntime() {
        prefs.edit().remove(KEY_RUNTIME).commit()
        refreshWidgets()
    }

    fun builtInProfiles(): List<PomodoroProfile> = listOf(
        PomodoroProfile(
            id = "builtin-classic",
            name = appContext.getString(R.string.fg_pomodoro_profile_classic),
            builtIn = true,
            config = DEFAULT_CONFIG
        ),
        PomodoroProfile(
            id = "builtin-deep",
            name = appContext.getString(R.string.fg_pomodoro_profile_deep),
            builtIn = true,
            config = DEFAULT_CONFIG.copy(
                focusMinutes = 50,
                shortBreakMinutes = 10,
                longBreakMinutes = 30
            )
        ),
        PomodoroProfile(
            id = "builtin-sprint",
            name = appContext.getString(R.string.fg_pomodoro_profile_sprint),
            builtIn = true,
            config = DEFAULT_CONFIG.copy(
                focusMinutes = 15,
                shortBreakMinutes = 3,
                longBreakMinutes = 10,
                longBreakEvery = 4
            )
        )
    )

    fun loadUserProfiles(): List<PomodoroProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                    val name = json.optString("name").trim().takeIf(String::isNotBlank) ?: continue
                    val configJson = json.optJSONObject("config") ?: continue
                    add(
                        PomodoroProfile(
                            id = id,
                            name = name.take(MAX_PROFILE_NAME_LENGTH),
                            config = configFromJson(configJson).normalized(),
                            builtIn = false
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun allProfiles(): List<PomodoroProfile> = builtInProfiles() + loadUserProfiles()

    fun saveProfile(name: String, config: PomodoroPlanConfig): PomodoroProfile? {
        val cleanName = name.trim().take(MAX_PROFILE_NAME_LENGTH)
        if (cleanName.isBlank()) return null
        val current = loadUserProfiles().toMutableList()
        if (current.size >= MAX_USER_PROFILES) return null

        val profile = PomodoroProfile(
            id = "user-${UUID.randomUUID()}",
            name = cleanName,
            config = config.normalized(),
            builtIn = false
        )
        current += profile
        writeProfiles(current)
        return profile
    }

    fun replaceProfile(profile: PomodoroProfile): Boolean {
        if (profile.builtIn) return false
        val profiles = loadUserProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) return false
        val cleanName = profile.name.trim().take(MAX_PROFILE_NAME_LENGTH)
        if (cleanName.isBlank()) return false
        profiles[index] = profile.copy(name = cleanName, config = profile.config.normalized())
        writeProfiles(profiles)
        return true
    }

    fun deleteProfile(id: String): Boolean {
        val current = loadUserProfiles()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        writeProfiles(updated)
        return true
    }

    private fun writeProfiles(profiles: List<PomodoroProfile>) {
        val array = JSONArray()
        profiles.take(MAX_USER_PROFILES).forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name.take(MAX_PROFILE_NAME_LENGTH))
                    .put("config", configToJson(profile.config.normalized()))
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).commit()
        refreshWidgets()
    }

    private fun refreshWidgets() {
        runCatching { PomodoroWidgetProvider.requestUpdate(appContext) }
    }

    companion object {
        const val MAX_USER_PROFILES = 20
        const val MAX_PROFILE_NAME_LENGTH = 40

        val DEFAULT_CONFIG = PomodoroPlanConfig()

        private const val KEY_CONFIG = "pomodoro.plan.config.v2"
        private const val KEY_RUNTIME = "pomodoro.plan.runtime.v2"
        private const val KEY_PROFILES = "pomodoro.plan.profiles.v2"

        private fun configToJson(config: PomodoroPlanConfig): JSONObject = JSONObject()
            .put("focusMinutes", config.focusMinutes)
            .put("shortBreakMinutes", config.shortBreakMinutes)
            .put("longBreakMinutes", config.longBreakMinutes)
            .put("longBreakEvery", config.longBreakEvery)
            .put("targetSessions", config.targetSessions)
            .put("soundEnabled", config.soundEnabled)
            .put("vibrationEnabled", config.vibrationEnabled)
            .put("alarmDurationSeconds", config.alarmDurationSeconds)
            .put("soundIndex", config.soundIndex)
            .put("silenceNotifications", config.silenceNotifications)
            .put("hideNotifications", config.hideNotifications)
            .put("strictBlocking", config.strictBlocking)

        private fun configFromJson(json: JSONObject): PomodoroPlanConfig = PomodoroPlanConfig(
            focusMinutes = json.optInt("focusMinutes", 25),
            shortBreakMinutes = json.optInt("shortBreakMinutes", 5),
            longBreakMinutes = json.optInt("longBreakMinutes", 15),
            longBreakEvery = json.optInt("longBreakEvery", 4),
            targetSessions = json.optInt("targetSessions", 0),
            soundEnabled = json.optBoolean("soundEnabled", true),
            vibrationEnabled = json.optBoolean("vibrationEnabled", true),
            alarmDurationSeconds = json.optInt("alarmDurationSeconds", 5),
            soundIndex = json.optInt("soundIndex", 0),
            silenceNotifications = json.optBoolean("silenceNotifications", false),
            hideNotifications = json.optBoolean("hideNotifications", false),
            strictBlocking = json.optBoolean("strictBlocking", false)
        )
    }
}
