package com.bloemstudios.admin.network

import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseQueryString

enum class OriginProblem { HTTPS_REQUIRED, HOST_REQUIRED, ORIGIN_ONLY, MALFORMED }

class InvalidServerOrigin(val problem: OriginProblem) : IllegalArgumentException(problem.name)

@JvmInline
value class ServerOrigin private constructor(val value: String) {
    /** The origin in the server's `iss` form: lower-case scheme and host, default port omitted, no path or trailing slash. */
    val issuer: String get() = value

    /**
     * [path] is already percent-encoded by the typed API layer (ids through `encodeURLPathPart`,
     * the query through `appendQuery`), so it is attached as given. Decoding it first would turn an
     * id such as `a/b` into two segments, `a?b` into a query and `a#b` into a fragment.
     */
    fun resolve(path: String): Url {
        val trimmed = path.trimStart('/')
        val rawPath = trimmed.substringBefore('?')
        val hasQuery = '?' in trimmed
        val rawQuery = trimmed.substringAfter('?', "")
        return URLBuilder(value).apply {
            // A bare dot segment is resolved away by HTTP clients and servers; send it as an id.
            encodedPathSegments = rawPath.split('/').map { if (it == "." || it == "..") it.replace(".", "%2E") else it }
            if (rawQuery.isNotEmpty()) encodedParameters.appendAll(parseQueryString(rawQuery, decode = false))
            trailingQuery = hasQuery && rawQuery.isEmpty()
        }.build()
    }

    companion object {
        /**
         * An address an administrator typed: without a scheme it is taken as HTTPS, so
         * `media.example.com` works. Any scheme that is given is kept and checked, so `http://` is
         * still refused, as are paths, queries and sign-in details.
         */
        fun parseTyped(raw: String): ServerOrigin {
            val candidate = raw.trim()
            return parse(if ("://" in candidate) candidate else "https://$candidate")
        }

        fun parse(raw: String): ServerOrigin {
            val candidate = raw.trim()
            // Ktor discards an empty fragment, so reject the raw delimiter before parsing.
            if ('#' in candidate) throw InvalidServerOrigin(OriginProblem.ORIGIN_ONLY)
            val url = try {
                Url(candidate)
            } catch (_: URLParserException) {
                throw InvalidServerOrigin(OriginProblem.MALFORMED)
            }
            if (url.protocol != URLProtocol.HTTPS) throw InvalidServerOrigin(OriginProblem.HTTPS_REQUIRED)
            val authority = candidate.substringAfter("://", "").substringBefore('/').substringBefore('?')
            if (authority.isBlank() || url.host.isBlank()) throw InvalidServerOrigin(OriginProblem.HOST_REQUIRED)
            if ('@' in authority || url.user != null || url.password != null ||
                url.encodedPath !in setOf("", "/") ||
                url.parameters.names().isNotEmpty() || url.trailingQuery || url.fragment.isNotBlank()
            ) throw InvalidServerOrigin(OriginProblem.ORIGIN_ONLY)
            val canonical = URLBuilder(url).apply {
                host = url.host.lowercase()
                encodedPathSegments = emptyList()
            }.buildString().removeSuffix("/")
            return ServerOrigin(canonical)
        }
    }
}
