package com.focusguard.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ForumErrorCode {
    NETWORK,
    UNAUTHORIZED,
    INVALID_TOKEN,
    BANNED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    BUSY,
    BAD_REQUEST,
    NOT_CONFIGURED,
    SERVER;

    companion object {
        /** Codes sent by forum-backend/Code.gs in `{"ok":false,"error":"..."}`. */
        fun fromServer(value: String): ForumErrorCode = when (value) {
            "unauthorized" -> UNAUTHORIZED
            "invalid_token" -> INVALID_TOKEN
            "banned" -> BANNED
            "forbidden" -> FORBIDDEN
            "not_found" -> NOT_FOUND
            "rate_limited" -> RATE_LIMITED
            "busy" -> BUSY
            "bad_request", "unsupported_version" -> BAD_REQUEST
            "not_configured" -> NOT_CONFIGURED
            else -> SERVER
        }
    }
}

class ForumApiException(
    val code: ForumErrorCode,
    cause: Throwable? = null
) : IOException("Forum request failed: $code", cause)

/** Sends one request envelope and returns the backend's raw response envelope. */
fun interface ForumTransport {
    suspend fun send(request: JSONObject): JSONObject
}

/**
 * Talks to the Apps Script web app.
 *
 * Apps Script answers a POST with a 302 to script.googleusercontent.com, where the
 * JSON is fetched with GET. Redirects are followed here, by hand, so the switch to
 * GET does not depend on the platform's HttpURLConnection and never leaves HTTPS.
 */
class HttpForumTransport(private val endpointUrl: String) : ForumTransport {

    override suspend fun send(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val raw = try {
            execute(request.toString().toByteArray(Charsets.UTF_8))
        } catch (error: ForumApiException) {
            throw error
        } catch (error: IOException) {
            throw ForumApiException(ForumErrorCode.NETWORK, error)
        }
        try {
            JSONObject(raw)
        } catch (error: org.json.JSONException) {
            // An HTML page here means the web app is not deployed for "Anyone".
            throw ForumApiException(ForumErrorCode.NOT_CONFIGURED, error)
        }
    }

    private fun execute(body: ByteArray): String {
        var url = URL(endpointUrl)
        var post = true
        repeat(MAX_REDIRECTS + 1) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (post) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { stream -> stream.write(body) }
                }

                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw ForumApiException(ForumErrorCode.SERVER)
                    val next = URL(url, location)
                    if (next.protocol != "https") throw ForumApiException(ForumErrorCode.SERVER)
                    url = next
                    // 307/308 keep the method and body; 301/302/303 continue as GET.
                    post = post && (status == 307 || status == 308)
                    return@repeat
                }
                if (status == 429) throw ForumApiException(ForumErrorCode.RATE_LIMITED)
                if (status !in 200..299) throw ForumApiException(ForumErrorCode.SERVER)
                return connection.inputStream.use(::readLimited)
            } finally {
                connection.disconnect()
            }
        }
        throw ForumApiException(ForumErrorCode.SERVER)
    }

    private fun readLimited(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_RESPONSE_BYTES) throw ForumApiException(ForumErrorCode.SERVER)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        // Apps Script can take several seconds on a cold start.
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
