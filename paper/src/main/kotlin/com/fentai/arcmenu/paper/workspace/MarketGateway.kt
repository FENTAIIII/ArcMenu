package com.fentai.arcmenu.paper.workspace

import com.fentai.arcmenu.paper.localization.localizedRequire
import com.fentai.arcmenu.paper.localization.localizedArgumentFailure
import com.fentai.arcmenu.core.pack.ArcPackIO
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import com.fentai.arcmenu.protocol.MarketTls

/** Downloads occur off the region thread; archive installation stays in the caller's transaction. */
internal class MarketGateway(private val configuredUrl: () -> String, private val configuredCertificate: () -> String, private val temporaryDirectory: Path) {
    @Volatile private var clientPin: String? = null
    @Volatile private var pinnedClient: HttpClient? = null
    private fun client(): HttpClient = synchronized(this) {
        val pin = configuredCertificate().trim()
        if (clientPin != pin || pinnedClient == null) {
            pinnedClient = MarketTls.httpClient(pin); clientPin = pin
        }
        pinnedClient!!
    }
    fun base(): String {
        val url = configuredUrl().trim().trimEnd('/')
        localizedRequire(url.isNotEmpty(), "workspace.market-url-missing", "Marketplace URL is not configured on this server")
        val uri = URI(url)
        localizedRequire(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1")), "workspace.market-url-invalid", "Marketplace URL must use HTTPS; local development may use HTTP loopback")
        return url
    }
    private fun request(path: String, token: String): HttpRequest.Builder = HttpRequest.newBuilder(URI(base() + path))
        .timeout(Duration.ofSeconds(120)).apply {
            if (token.isNotBlank()) {
                localizedRequire(token.length <= 4096 && token.all { it.code in 33..126 }, "workspace.market-session-invalid", "Invalid marketplace session")
                header("Authorization", "Bearer $token")
            }
        }
    fun metadata(id: String, token: String = ""): JsonObject {
        ArcPackIO.validId(id)
        val response = client().send(request("/v1/packs/$id", token).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use { input ->
            val bytes = input.readNBytes(1_048_577); localizedRequire(bytes.size <= 1_048_576, "workspace.market-response-too-large", "Marketplace response is too large"); String(bytes, Charsets.UTF_8)
        }
        if (response.statusCode() != 200) {
            val reason = errorMessage(text)
            throw localizedArgumentFailure("workspace.market-request-failed", "Marketplace request failed (${response.statusCode()}): $reason", response.statusCode(), reason)
        }
        return JsonParser.parseString(text).asJsonObject
    }
    fun download(id: String, version: String, token: String = ""): Path {
        ArcPackIO.validId(id)
        val selectedVersion = if (version.isBlank()) metadata(id, token).required("latestVersion") else version
        localizedRequire(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}").matches(selectedVersion), "workspace.pack-version-invalid", "Invalid package version")
        Files.createDirectories(temporaryDirectory)
        val file = temporaryDirectory.resolve("${UUID.randomUUID()}.arcpack")
        try {
            val response = client().send(request("/v1/packs/$id/versions/$selectedVersion/download", token).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                localizedRequire(response.statusCode() == 200, "workspace.market-download-failed", "Marketplace download failed (${response.statusCode()})", response.statusCode())
                localizedRequire(response.headers().firstValueAsLong("Content-Length").orElse(0) <= 128L * 1024 * 1024, "workspace.market-pack-too-large", "Marketplace package exceeds 128 MB")
                Files.newOutputStream(file).use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; localizedRequire(total <= 128L * 1024 * 1024, "workspace.market-pack-too-large", "Marketplace package exceeds 128 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val manifest = ArcPackIO.inspect(file)
            localizedRequire(manifest.id == id && manifest.version == selectedVersion, "workspace.pack-identity-invalid", "Downloaded package identity does not match the request")
            return file
        } catch (error: Exception) { Files.deleteIfExists(file); throw error }
    }
    fun publish(file: Path, id: String, token: String): JsonObject {
        ArcPackIO.validId(id); localizedRequire(token.isNotBlank(), "workspace.publish-sign-in-required", "Sign in with a genuine Minecraft account before publishing")
        val response = client().send(request("/v1/packs/$id/versions", token).header("Content-Type", "application/zip")
            .POST(HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use { input ->
            val bytes = input.readNBytes(1_048_577); localizedRequire(bytes.size <= 1_048_576, "workspace.market-response-too-large", "Marketplace response is too large"); String(bytes, Charsets.UTF_8)
        }
        if (response.statusCode() !in 200..299) {
            val reason = errorMessage(text)
            throw localizedArgumentFailure("workspace.market-publish-failed", "Marketplace publication failed (${response.statusCode()}): $reason", response.statusCode(), reason)
        }
        return JsonParser.parseString(text).asJsonObject
    }
    private fun errorMessage(text: String): String = runCatching {
        JsonParser.parseString(text).asJsonObject.getAsJsonObject("error").required("message")
    }.getOrDefault("Request rejected").take(1024)
}
