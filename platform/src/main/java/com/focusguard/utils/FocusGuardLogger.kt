package com.focusguard.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Logger privado do FocusGuard. Nenhuma falha de logging pode alterar o fluxo
 * funcional do aplicativo, inclusive em testes JVM onde android.util.Log não
 * possui implementação real.
 */
object FocusGuardLogger {
    private const val TAG = "FocusGuardLogger"
    private const val MAX_BREADCRUMBS = 50
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val breadcrumbs = ConcurrentLinkedQueue<String>()

    @Volatile private var isInitialized = false
    @Volatile private var logFile: File? = null

    private val fullDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    private val shortTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    private val dayDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    fun init(context: Context) {
        if (isInitialized) return
        try {
            val directory = File(context.filesDir, "FocusGuardLogs").also {
                if (!it.exists() && !it.mkdirs()) {
                    throw IllegalStateException("Não foi possível criar ${it.absolutePath}")
                }
            }
            val date = dayDateFormat.get()!!.format(Date())
            logFile = File(directory, "log_$date.txt")

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logFatal(thread.name, throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }

            isInitialized = true
            log("System", "Logger inicializado em ${directory.absolutePath}")
            logDeviceInfo(context)
            cleanOldLogs(directory)
        } catch (error: Exception) {
            safeAndroidError("Falha ao iniciar logger", error)
        }
    }

    fun log(tag: String, message: String) {
        runCatching {
            val timestamp = fullDateFormat.get()!!.format(Date())
            val formatted = "[$timestamp] [INFO] [$tag] -> $message"
            addBreadcrumb("$tag: $message")
            writeToDisk(formatted)
            safeAndroidInfo(formatted)
        }
    }

    fun addBreadcrumb(action: String) {
        runCatching {
            breadcrumbs.add("${shortTimeFormat.get()!!.format(Date())}: $action")
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.poll()
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            val timestamp = fullDateFormat.get()!!.format(Date())
            val errorMessage =
                "[$timestamp] [ERROR] [$tag] -> $message | Error: ${throwable?.message}"
            val stackTrace = throwable?.stackTraceToString() ?: "Sem stacktrace"
            writeToDisk("$errorMessage\nSTACKTRACE:\n$stackTrace")
            safeAndroidError(errorMessage, throwable)
        }
    }

    private fun logFatal(threadName: String, throwable: Throwable) {
        val timestamp = runCatching { fullDateFormat.get()!!.format(Date()) }
            .getOrDefault(System.currentTimeMillis().toString())
        val header = buildString {
            appendLine()
            appendLine("=".repeat(50))
            appendLine("[$timestamp] [FATAL] [CRASH] -> Thread: $threadName")
            appendLine("ERRO: ${throwable.javaClass.simpleName}: ${throwable.message}")
            appendLine("BREADCRUMBS (últimas ações):")
            appendLine(breadcrumbs.joinToString("\n"))
            append("=".repeat(50))
        }

        try {
            logFile?.let { file ->
                FileOutputStream(file, true).use { stream ->
                    stream.write(
                        "$header\nSTACKTRACE:\n${throwable.stackTraceToString()}\n".toByteArray()
                    )
                    stream.flush()
                }
            }
        } catch (loggingError: Exception) {
            safeAndroidError("Erro ao gravar crash fatal", loggingError)
        }
    }

    private fun logDeviceInfo(context: Context) {
        runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            val info = """
                [DEVICE INFO]
                Model: ${Build.MANUFACTURER} ${Build.MODEL}
                Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                RAM: ${memoryInfo.availMem / 1024 / 1024}MB livres de ${memoryInfo.totalMem / 1024 / 1024}MB
                Storage: ${logFile?.parentFile?.usableSpace?.div(1024 * 1024) ?: "N/A"}MB disponíveis
                --------------------------------------------------
            """.trimIndent()
            writeToDisk(info)
        }
    }

    private fun writeToDisk(message: String) {
        val target = logFile ?: return
        scope.launch {
            mutex.withLock {
                try {
                    FileOutputStream(target, true).use { stream ->
                        stream.write("$message\n".toByteArray())
                    }
                } catch (error: Exception) {
                    safeAndroidError("Erro ao escrever log no disco", error)
                }
            }
        }
    }

    private fun cleanOldLogs(directory: File) {
        scope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                directory.listFiles().orEmpty().forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
            }
        }
    }

    private fun safeAndroidInfo(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log não é implementado em testes JVM puros.
        }
    }

    private fun safeAndroidError(message: String, throwable: Throwable?) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: RuntimeException) {
            // Logging nunca deve derrubar a operação que tentou registrar o erro.
        }
    }
}

