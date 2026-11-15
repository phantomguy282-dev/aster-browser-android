package app.aster.mobile

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Local format checks are not antivirus scanning and never produce a SAFE verdict. */
object DownloadRisk {
    enum class Verdict { UNVERIFIED, SUSPICIOUS, DANGEROUS }
    data class Report(val verdict: Verdict, val sha256: String, val bytes: Long, val reasons: List<String>, val malwareScanned: Boolean = false)
    private val executable = setOf("apk", "aab", "exe", "msi", "com", "bat", "cmd", "ps1", "vbs", "js", "jar", "dmg", "pkg", "sh", "scr", "dll")
    fun safeName(input: String): String {
        var name = input.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f\\u202a-\\u202e\\u2066-\\u2069]"), "_").trim().trimEnd('.', ' ')
        if (name.isBlank() || name == "." || name == "..") name = "download"
        if (Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)) name = "_$name"
        return name.take(180)
    }
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun executableName(name: String): Boolean = extension(name) in executable
    fun inspect(file: File, mime: String?, expectedSha256: String? = null, maxBytes: Long = 100L * 1024 * 1024): Report {
        require(file.isFile && file.length() <= maxBytes) { "File missing or exceeds inspection limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(16)
        var prefixSize = 0
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= maxBytes) { "File changed or exceeds inspection limit" }
                if (prefixSize < prefix.size) {
                    val copy = minOf(n, prefix.size - prefixSize)
                    buffer.copyInto(prefix, prefixSize, 0, copy)
                    prefixSize += copy
                }
                digest.update(buffer, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val reasons = mutableListOf("No antivirus engine scanned this file. A hash is an identifier, not proof of safety.")
        var suspicious = executableName(file.name)
        if (suspicious) reasons += "This filename indicates executable or active content. Never install or open it unless you trust its source."
        if (prefixSize >= 2 && prefix[0] == 0x4d.toByte() && prefix[1] == 0x5a.toByte()) {
            suspicious = true; reasons += "File has a Windows executable signature."
        }
        val ext = extension(file.name)
        val looksPng = prefixSize >= 8 && prefix.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val looksPdf = prefixSize >= 5 && String(prefix, 0, 5, Charsets.US_ASCII) == "%PDF-"
        if ((ext == "png" && !looksPng) || (ext == "pdf" && !looksPdf)) { suspicious = true; reasons += "Filename does not match the expected format signature." }
        if (mime?.startsWith("image/") == true && executableName(file.name)) { suspicious = true; reasons += "Declared MIME type conflicts with executable filename." }
        if (expectedSha256 != null) {
            require(Regex("^[a-fA-F0-9]{64}$").matches(expectedSha256)) { "Invalid expected SHA-256" }
            if (!hash.equals(expectedSha256, true)) { suspicious = true; reasons += "SHA-256 does not match the explicitly supplied expected value." }
            else reasons += "SHA-256 matches the supplied value; this does not establish safety."
        }
        return Report(if (suspicious) Verdict.SUSPICIOUS else Verdict.UNVERIFIED, hash, total, reasons)
    }
}
