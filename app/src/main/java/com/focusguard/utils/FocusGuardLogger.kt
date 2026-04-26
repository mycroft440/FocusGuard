package com.focusguard.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FocusGuardLogger {

    private const val TAG = "FocusGuardLogger"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        if (isInitialized) return

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val fgFolder = File(downloadsDir, "FocusGuardLogs")
            if (!fgFolder.exists()) {
                fgFolder.mkdirs()
            }

            val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            logFile = File(fgFolder, "log_$dateString.txt")

            setupCrashInterceptor()
            isInitialized = true

            log("System", "Logger Inicializado com sucesso. Caminho: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar o FocusGuardLogger", e)
        }
    }

    private fun setupCrashInterceptor() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            // Usa chamada síncrona/direta para garantir que escreve antes do Android matar o processo
            writeSync("CRASH_FATAL", "Crash na thread ${thread.name}: ${throwable.message}\n$stackTrace")
            
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        val level = if (isError) "ERROR" else "INFO"
        val threadName = Thread.currentThread().name
        val time = dateFormat.format(Date())
        val logLine = "[$time] [$level] [$tag] ($threadName) -> $message\n"
        
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.i(tag, message)
        }

        scope.launch {
            mutex.withLock {
                writeToFile(logLine)
            }
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        log(tag, fullMessage, isError = true)
    }

    private fun writeSync(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val logLine = "[$time] [FATAL] [$tag] -> $message\n"
        Log.e(tag, logLine)
        writeToFile(logLine) // Escreve direto na thread atual (usado para crashes)
    }

    private fun writeToFile(logLine: String) {
        if (logFile == null) return
        try {
            FileOutputStream(logFile!!, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.append(logLine)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao escrever no arquivo de log: ${e.message}")
        }
    }
}
