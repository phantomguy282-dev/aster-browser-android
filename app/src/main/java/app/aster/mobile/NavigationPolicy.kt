package app.aster.mobile

import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Pure JVM policy: no Android context, cookies, network requests or native bridge. */
object NavigationPolicy {
    private val searches = mapOf(
        "google" to "https://www.google.com/search?q=",
        "bing" to "https://www.bing.com/search?q=",
        "duckduckgo" to "https://duckduckgo.com/?q="
    )

    fun webAddress(value: String): String {
        require(value.length <= 8192) { "Address exceeds the supported length" }
        require(value.none { it <= '\u0020' || it == '\u007f' || it == '\\' }) { "Invalid address characters" }
        val parsed = URI(value)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Only HTTP and HTTPS websites are supported" }
        val authority = parsed.rawAuthority ?: throw IllegalArgumentException("Address needs a hostname")
        require(!authority.contains('@') && parsed.rawUserInfo == null) { "Embedded credentials are not allowed" }
        val host: String
        val port: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            require(end > 1) { "Invalid IPv6 address" }
            host = authority.substring(0, end + 1)
            require(URI("$scheme://$host/").host != null) { "Invalid IPv6 address" }
            port = authority.substring(end + 1)
        } else {
            require(authority.count { it == ':' } <= 1) { "IPv6 addresses require brackets" }
            val separator = authority.lastIndexOf(':')
            val rawHost = if (separator >= 0) authority.substring(0, separator) else authority
            port = if (separator >= 0) authority.substring(separator) else ""
            require(rawHost.isNotEmpty() && !rawHost.contains('%')) { "Invalid hostname" }
            host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(host.length <= 253 && host.removeSuffix(".").split('.').all {
                it.isNotEmpty() && it.length <= 63 && !it.startsWith('-') && !it.endsWith('-')
            }) { "Invalid hostname" }
        }
        if (port.isNotEmpty()) {
            require(port.startsWith(':') && port.drop(1).matches(Regex("[0-9]{1,5}"))) { "Invalid port" }
            require(port.drop(1).toInt() in 1..65535) { "Port is out of range" }
        }
        val path = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        require(path.startsWith('/')) { "Invalid path" }
        return "$scheme://$host$port$path" +
            (parsed.rawQuery?.let { "?$it" } ?: "") +
            (parsed.rawFragment?.let { "#$it" } ?: "")
    }

    fun isWebAddress(value: String?): Boolean = value != null && runCatching { webAddress(value) }.isSuccess

    fun resolve(input: String, searchEngine: String = "google"): String {
        require(input.length <= 8192) { "Input exceeds the supported length" }
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter an address or search" }
        require(value.none { it < '\u0020' || it == '\u007f' || it == '\\' }) { "Invalid input characters" }
        if (value.startsWith("//")) return webAddress("https:$value")
        val hostPort = Regex("^(?:localhost|[^\\s/:]+\\.[^\\s/:]+|\\[[0-9a-fA-F:]+\\]):[0-9]+(?:[/?#]|$)").containsMatchIn(value)
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value) && !hostPort) return webAddress(value)
        val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (value.none { it.isWhitespace() } && (authority.contains('.') || authority.startsWith('[') || authority.substringBefore(':').equals("localhost", true))) {
            val local = authority.equals("localhost", true) || authority.startsWith("localhost:", true) ||
                authority.startsWith("127.") || authority == "[::1]" || authority.startsWith("[::1]:")
            return webAddress((if (local) "http://" else "https://") + value)
        }
        return (searches[searchEngine] ?: searches.getValue("google")) + URLEncoder.encode(value, "UTF-8")
    }

    fun origin(value: String): String {
        val uri = URI(webAddress(value))
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    fun secureForPermissions(value: String): Boolean = runCatching {
        val uri = URI(webAddress(value))
        uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1", "[::1]")
    }.getOrDefault(false)
}
