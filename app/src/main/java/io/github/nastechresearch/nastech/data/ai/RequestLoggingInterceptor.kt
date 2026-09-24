package io.github.nastechresearch.nastech.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders.redacted(),
                    requestBody = requestBody.redacted(),
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders.redacted(),
                requestBody = requestBody.redacted(),
                responseCode = response.code,
                responseHeaders = responseHeaders.redacted(),
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    private fun Map<String, String>.redacted(): Map<String, String> =
        mapValues { (name, value) ->
            if (isSensitiveHeader(name)) "<redacted>" else value
        }

    private fun String?.redacted(): String? {
        if (this == null) return null
        return replace(
            Regex("""("(?:api[_-]?key|authorization|access[_-]?token|token|secret|password)"\s*:\s*")([^"]*)(")""", RegexOption.IGNORE_CASE),
            "$1<redacted>$3",
        )
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "authorization" ||
            normalized.contains("api-key") ||
            normalized.contains("apikey") ||
            normalized.contains("access-token") ||
            normalized == "token" ||
            normalized.contains("secret") ||
            normalized == "cookie" ||
            normalized == "set-cookie"
    }
}
