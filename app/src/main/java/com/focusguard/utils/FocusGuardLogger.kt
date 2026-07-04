package com.focusguard.utils

import kotlinx.coroutines.launch
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object FocusGuardLogger {
    private const val TAG = "FocusGuardLogger"
    private val mutex = Mutex()
    private var isInitialized = false
    private var logFile: File? = null

    // [B1] SimpleDateFormat cacheado como ThreadLocal (evita criação de novo objeto a cada log)
    private val fullDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    private val shortTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    private val dayDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        if (isInitialized) return

        try {
            // PRIVACIDADE: logs são escritos em armazenamento PRIVADO do app
            // (context.filesDir). Antes eram escritos em Environment.DIRECTORY_DOWNLOADS
            // (pasta pública), o que permitia que qualquer app com permissão de storage
            // lesse logs de comportamento do usuário — violação de privacidade para um
            // app de bloqueio. Arquivos em filesDir são sandboxed pelo SO e não requerem
            // permissão de storage.
            val focusGuardDir = File(context.filesDir, "FocusGuardLogs").also { dir ->
                if (!dir.exists()) dir.mkdirs()
            }

            val dateStr = dayDateFormat.get()!!.format(Date())
            logFile = File(focusGuardDir, "log_$dateStr.txt")

            // Configura o interceptor de crashes globais
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logFatal(thread.name, throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }

            isInitialized = true
            log("System", "Logger Inicializado. Local: ${focusGuardDir.absolutePath}")
            logDeviceInfo(context)
            cleanOldLogs(focusGuardDir)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar logger", e)
        }
    }

    // Breadcrumbs: Rastro das últimas ações do usuário
    private val breadcrumbs = ConcurrentLinkedQueue<String>()
    private const val MAX_BREADCRUMBS = 50

    fun log(tag: String, message: String) {
        val timestamp = fullDateFormat.get()!!.format(Date())
        val formattedMsg = "[$timestamp] [INFO] [$tag] -> $message"
        addBreadcrumb("$tag: $message")
        writeToDisk(formattedMsg)
        Log.i(TAG, formattedMsg)
    }

    fun addBreadcrumb(action: String) {
        breadcrumbs.add("${shortTimeFormat.get()!!.format(Date())}: $action")
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.poll()
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = fullDateFormat.get()!!.format(Date())
        val errorMsg = "[$timestamp] [ERROR] [$tag] -> $message | Error: ${throwable?.message}"
        val stackTrace = throwable?.let { Log.getStackTraceString(it) } ?: "Sem stacktrace"
        
        writeToDisk("$errorMsg\nSTACKTRACE:\n$stackTrace")
        Log.e(TAG, errorMsg, throwable)
    }

    private fun logFatal(threadName: String, throwable: Throwable) {
        val timestamp = fullDateFormat.get()!!.format(Date())
        val fatalHeader = "\n" + "=".repeat(50) + "\n" +
                "[$timestamp] [FATAL] [CRASH] -> Thread: $threadName\n" +
                "ERRO: ${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                "BREADCRUMBS (Últimas ações):\n" +
                breadcrumbs.joinToString("\n") + "\n" +
                "=".repeat(50)

        val stackTrace = Log.getStackTraceString(throwable)

        // IMPORTANTE: NÃO usar runBlocking aqui.
        // Antes esta função usava runBlocking { mutex.withLock { ... } } para
        // garantir a escrita antes do app morrer. Porém, se o crash ocorreu
        // dentro de uma coroutine (caso comum), runBlocking tenta aguardar
        // a liberação do mutex que pode nunca chegar — causando deadlock
        // silencioso e perdendo o log do crash.
        //
        // Agora escrevemos síncrono sem mutex: o pior caso é duas threads
        // escrevendo ao mesmo tempo e corrompendo parcialmente o arquivo,
        // mas em cenário de FATAL crash isso é aceitável — ter o log é mais
        // importante que ter o log perfeitamente formatado.
        try {
            logFile?.let { file ->
                FileOutputStream(file, true).use { stream ->
                    stream.write("$fatalHeader\nSTACKTRACE:\n$stackTrace\n".toByteArray())
                    stream.flush()
                }
            }
        } catch (e: Throwable) {
            // Mesmo no fallback, não relançar — defaultHandler precisa rodar
            Log.e(TAG, "Erro ao gravar crash fatal", e)
        }
    }

    private fun logDeviceInfo(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
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

    private fun writeToDisk(message: String) {
        scope.launch {
            mutex.withLock {
                try {
                    logFile?.let { file ->
                        FileOutputStream(file, true).use { it.write("$message\n".toByteArray()) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao escrever log no disco", e)
                }
            }
        }
    }

    private fun cleanOldLogs(dir: File) {
        scope.launch {
            val files = dir.listFiles() ?: return@launch
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            files.forEach { file ->
                if (file.lastModified() < sevenDaysAgo) {
                    file.delete()
                }
            }
        }
    }
}
