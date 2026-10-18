package api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Public avatar proxy. Telegram profile-photo URLs (t.me/i/userpic/…) 302-redirect to
 * a CDN and send no CORS header, so a WebGL canvas (the PvP wheel) can't use them as a
 * texture — the letter fallback shows instead. We re-serve the bytes with an
 * Access-Control-Allow-Origin header so the game can texture them.
 *
 * Unauthenticated on purpose (the game client holds no operator secret) and SSRF-guarded
 * to Telegram hosts only. Buffered (avatars are a few KB) and cached a day at the edge.
 */
fun Application.avatarApi() {
    val client = HttpClient(CIO) // follows redirects by default

    routing {
        get("/api/v1/avatar") {
            val url = call.request.queryParameters["u"]
            if (url.isNullOrBlank() || !isTelegramImage(url)) {
                call.respond(HttpStatusCode.BadRequest, "bad url")
                return@get
            }

            val resp = runCatching { client.get(url) }.getOrNull()
            if (resp == null || !resp.status.isSuccess()) {
                call.respond(HttpStatusCode.BadGateway, "fetch failed")
                return@get
            }

            val bytes: ByteArray = resp.body()
            val contentType = resp.headers[HttpHeaders.ContentType]
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Image.JPEG

            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, contentType)
        }
    }
}

private fun isTelegramImage(raw: String): Boolean {
    val host = runCatching { Url(raw).host.lowercase() }.getOrNull() ?: return false
    return host == "t.me" ||
        host.endsWith(".t.me") ||
        host.endsWith(".telegram.org") ||
        host.endsWith(".telegram-cdn.org") ||
        host.endsWith(".cdn-telegram.org")
}
