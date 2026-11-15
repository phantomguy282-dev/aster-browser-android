package app.aster.mobile

import java.net.URI
import java.util.UUID

/**
 * Pluggable sealing for password bytes. The Android app wires
 * [PasswordCrypto] (Keystore AES-256-GCM); JVM tests inject a fake.
 * Implementations must be authenticated encryption and never log.
 */
interface PasswordSealer {
    fun encrypt(plain: CharArray): ByteArray
    fun decrypt(sealed: ByteArray): CharArray
}

/**
 * ASTER Android Password Store — pure logic, testable on the JVM.
 *
 * Records are bound to an exact normalized HTTPS origin: [forOrigin] only
 * returns credentials when the browsing URL's origin equals the record
 * origin, so cross-origin autofill cannot happen through this API.
 * Persistence (JSON via BrowserStore) is applied by MainActivity.
 */
class PasswordStore(private val sealer: PasswordSealer) {
    data class Record(
        val id: String,
        val origin: String,          // exact https://host[:port] origin
        var username: String,
        var title: String,
        var notes: String,
        var dateCreated: Long,
        var dateUpdated: Long,
        @Volatile var sealed: ByteArray? = null
    ) {
        fun password(sealer: PasswordSealer): String = String(sealer.decrypt(sealed ?: throw IllegalStateException("No stored password")))
        fun setPassword(sealer: PasswordSealer, value: CharArray) { sealed = sealer.encrypt(value); value.fill('\u0000') }
    }

    val records = mutableListOf<Record>()
    var onChanged: (() -> Unit)? = null

    companion object {
        const val MAX_RECORDS = 300
        fun originOf(url: String): String? {
            if (url.length > 2048) return null
            val uri = runCatching { URI(NavigationPolicy.webAddress(url)) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https") return null
            if (!uri.userInfo.isNullOrEmpty()) return null
            val host = uri.host?.lowercase() ?: return null
            if (host.isEmpty() || host.length > 253) return null
            val port = uri.port
            return if (port > 0) "https://$host:$port" else "https://$host"
        }
        fun sanitize(value: String, limit: Int) = value.replace(Regex("[\\u0000-\\u001f\\u007f\\u202a-\\u202e\\u2066-\\u2069]"), " ").trim().take(limit)
    }

    private fun changed() { onChanged?.invoke() }

    @Synchronized
    fun add(url: String, username: String, password: CharArray, title: String = "", notes: String = ""): Record {
        val origin = originOf(url) ?: throw IllegalArgumentException("Passwords can only be saved for HTTPS websites")
        require(username.length <= 200) { "Username too long" }
        require(password.isNotEmpty() && password.size <= 1024) { "Password must be 1–1024 characters" }
        require(records.size < MAX_RECORDS) { "Vault limit of $MAX_RECORDS reached" }
        val now = System.currentTimeMillis()
        val record = Record(
            id = UUID.randomUUID().toString(),
            origin = origin,
            username = sanitize(username, 200),
            title = sanitize(title, 200),
            notes = sanitize(notes, 2000),
            dateCreated = now, dateUpdated = now
        )
        record.setPassword(sealer, password) // CharArray zeroed inside
        records.add(record)
        changed()
        return record
    }

    @Synchronized
    fun forOrigin(url: String): List<Triple<String, String, CharArray>> {
        val origin = originOf(url) ?: return emptyList()
        return records.filter { it.origin == origin }.map { Triple(it.id, it.username, it.password(sealer).toCharArray()) }
    }

    @Synchronized
    fun remove(id: String) {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw IllegalArgumentException("Credential not found")
        records[index].sealed?.fill(0)
        records.removeAt(index)
        changed()
    }

    @Synchronized
    fun update(id: String, username: String? = null, password: CharArray? = null, title: String? = null, notes: String? = null) {
        val record = records.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Credential not found")
        username?.let { record.username = sanitize(it, 200) }
        title?.let { record.title = sanitize(it, 200) }
        notes?.let { record.notes = sanitize(it, 2000) }
        password?.let { require(it.isNotEmpty() && it.size <= 1024); record.setPassword(sealer, it) }
        record.dateUpdated = System.currentTimeMillis()
        changed()
    }

    fun search(query: String): List<Record> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return records.toList()
        return records.filter { it.origin.lowercase().contains(q) || it.username.lowercase().contains(q) || it.title.lowercase().contains(q) }
    }

    fun meta(record: Record): Map<String, Any> = mapOf(
        "id" to record.id, "origin" to record.origin, "username" to record.username,
        "title" to record.title, "notes" to record.notes,
        "created" to record.dateCreated, "updated" to record.dateUpdated
    )
}
