package app.aster.mobile

import android.app.Activity
import android.app.AlertDialog
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No transfer is created until the native confirmation is accepted. No auto-open. */
class DownloadGuard(private val activity: Activity, private val shield: MobileShield, private val privateSession: Boolean = false) {
    data class Item(val id: String, val name: String, val source: String, val mime: String, val date: Long,
        @Volatile var bytes: Long = 0, @Volatile var total: Long = -1, @Volatile var state: String = "Waiting",
        @Volatile var report: DownloadRisk.Report? = null, val cancelled: AtomicBoolean = AtomicBoolean(false), val busy: AtomicBoolean = AtomicBoolean(false))
    private val pool = Executors.newFixedThreadPool(2)
    private val root = File(activity.filesDir, "guard-downloads").apply { mkdirs() }
    private val records = File(activity.filesDir, "downloads.json")
    val items = mutableListOf<Item>()
    var onChanged: (() -> Unit)? = null
    @Volatile private var disposed = false
    init {
        if (!privateSession && records.isFile && records.length() < 1024 * 1024) runCatching {
            val rows = JSONArray(records.readText())
            for (i in 0 until minOf(rows.length(), 200)) {
                val row = rows.getJSONObject(i); val id = row.getString("id")
                if (!Regex("[a-f0-9-]{36}").matches(id)) continue
                val item = Item(id, DownloadRisk.safeName(row.getString("name")), row.getString("source"), row.optString("mime"), row.optLong("date"))
                item.bytes = row.optLong("bytes"); item.total = row.optLong("total", -1); item.state = row.optString("state")
                if (item.state in listOf("Downloading", "Waiting", "Checking", "Cancelling")) item.state = "Interrupted; retry from website"
                val f = file(item); if (f.isFile) item.report = DownloadRisk.inspect(f, item.mime)
                items += item
            }
        }
    }
    private fun file(item: Item) = File(File(root, item.id), item.name)
    private fun changed() { activity.runOnUiThread { if (!disposed && !activity.isFinishing) { persist(); onChanged?.invoke() } } }
    private fun persist() {
        if (privateSession) return
        val rows = JSONArray()
        for (item in items.take(200)) rows.put(JSONObject().put("id", item.id).put("name", item.name).put("source", item.source)
            .put("mime", item.mime).put("date", item.date).put("bytes", item.bytes).put("total", item.total).put("state", item.state))
        runCatching { val tmp = File(records.parentFile, "downloads.tmp"); tmp.writeText(rows.toString()); if (!tmp.renameTo(records)) tmp.delete() }
    }
    fun request(raw: String, disposition: String?, mime: String?, size: Long, userAgent: String?) {
        if (privateSession) { notice("Downloads in private mode are disabled in this preview to avoid persistent private traces."); return }
        val url = runCatching { NavigationPolicy.webAddress(raw) }.getOrElse { notice("Unsupported download address"); return }
        if (!url.startsWith("https://")) { notice("Download Guard requires HTTPS. No insecure downgrade is performed."); return }
        val decision = shield.decide(url, true)
        if (decision.blocked) { notice("Download source blocked by Mobile Shield: ${decision.category}"); return }
        if (items.size >= 200) { notice("Remove old download records before adding more."); return }
        val name = DownloadRisk.safeName(URLUtil.guessFileName(url, disposition, mime))
        val source = URL(url).host
        val warning = if (DownloadRisk.executableName(name)) "\nWarning: executable or active content. Never install untrusted files." else ""
        AlertDialog.Builder(activity).setTitle("ASTER DOWNLOAD GUARD")
            .setMessage("File: $name\nType: ${mime ?: "Unknown"}\nExtension: ${DownloadRisk.extension(name).ifEmpty { "Unknown" }}\nSize: ${if (size >= 0) "$size bytes" else "Unknown"}\nSource: $source\nTransport: HTTPS\nReputation: not checked\nScan: local format and SHA-256 only; antivirus unavailable.$warning\n\nDownloads use no browser cookies or authentication; protected links may fail. Maximum 100 MiB. Files never open automatically.")
            .setNegativeButton("Cancel", null).setPositiveButton("Download & check") { _, _ ->
                if (disposed || activity.isFinishing || items.size >= 200 || shield.decide(url, true).blocked) return@setPositiveButton
                val item = Item(UUID.randomUUID().toString(), name, url, mime ?: "application/octet-stream", System.currentTimeMillis(), total = size)
                item.busy.set(true); items.add(0, item); changed(); pool.execute { transfer(item, userAgent) }
            }.show()
    }
    private fun transfer(item: Item, agent: String?) {
        val directory = File(root, item.id).apply { mkdirs() }; val temporary = File(directory, ".aster-${UUID.randomUUID()}.part")
        var finalized: File? = null
        var connection: HttpURLConnection? = null
        try {
            var target = item.source; var redirects = 0
            while (true) {
                check(!item.cancelled.get()) { "Cancelled" }
                val valid = NavigationPolicy.webAddress(target)
                check(valid.startsWith("https://")) { "HTTPS downgrade rejected" }
                check(!shield.decide(valid, true).blocked) { "Redirect blocked by Mobile Shield" }
                connection = URL(valid).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (agent != null) connection.setRequestProperty("User-Agent", agent.take(1024))
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(redirects++ < 5) { "Redirect limit exceeded" }
                    val location = connection.getHeaderField("Location") ?: error("Redirect without location")
                    target = URL(URL(valid), location).toString(); connection.disconnect(); continue
                }
                check(status in 200..299) { "Server returned HTTP $status" }; break
            }
            val conn = connection ?: error("No connection")
            item.total = conn.getHeaderFieldLong("Content-Length", -1)
            check(item.total <= 100L * 1024 * 1024) { "Download exceeds 100 MiB" }
            item.state = "Downloading"; changed(); var last = 0L
            conn.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    check(!item.cancelled.get()) { "Cancelled" }
                    val n = input.read(buffer); if (n < 0) break
                    item.bytes += n; check(item.bytes <= 100L * 1024 * 1024) { "Download exceeds 100 MiB" }; output.write(buffer, 0, n)
                    if (System.currentTimeMillis() - last > 350) { changed(); last = System.currentTimeMillis() }
                }
            } }
            check(item.total < 0 || item.bytes == item.total) { "Incomplete download" }
            check(!item.cancelled.get()) { "Cancelled" }
            val result = file(item); check(!result.exists() && temporary.renameTo(result)) { "Unable to finalize download" }
            finalized = result; item.state = "Checking"; changed()
            val report = DownloadRisk.inspect(result, conn.contentType ?: item.mime)
            synchronized(item) {
                check(!item.cancelled.get()) { "Cancelled" }
                item.report = report; item.state = report.verdict.name
                item.busy.set(false)
            }
        } catch (e: Exception) {
            temporary.delete(); finalized?.delete(); item.report = null; item.state = if (item.cancelled.get()) "Cancelled" else "Failed: ${e.message?.take(180)}"
        } finally { connection?.disconnect(); item.busy.set(false); changed() }
    }
    fun cancel(item: Item) { synchronized(item) { if (item.busy.get()) { item.cancelled.set(true); item.state = "Cancelling" } }; changed() }
    fun remove(item: Item) {
        check(items.contains(item) && !item.busy.get()) { "Cancel and wait for transfer to stop before deleting" }
        val f = file(item); if (f.exists()) check(f.delete()) { "Could not delete file" }
        File(root, item.id).delete(); items.remove(item); changed()
    }
    fun reportText(item: Item): String = "ASTER DOWNLOAD GUARD\n${item.name}\nSource: ${item.source}\nDate: ${java.util.Date(item.date)}\nBytes: ${item.bytes}\nState: ${item.state}\nSHA-256: ${item.report?.sha256 ?: "Unavailable"}\nAntivirus scanned: false\n" + (item.report?.reasons?.joinToString("\n") ?: "No completed local inspection")
    fun recheck(item: Item) {
        check(!disposed && items.contains(item) && item.busy.compareAndSet(false, true)) { "Download is busy or unavailable" }
        item.cancelled.set(false); item.report = null; item.state = "Checking"; changed()
        pool.execute {
            try {
                val report = DownloadRisk.inspect(file(item), item.mime)
                synchronized(item) {
                    check(!item.cancelled.get()) { "Cancelled" }
                    item.report = report; item.state = report.verdict.name; item.busy.set(false)
                }
            } catch (_: Exception) { item.report = null; item.state = if (item.cancelled.get()) "Check cancelled" else "Check unavailable" }
            finally { item.busy.set(false); changed() }
        }
    }
    fun dispose() {
        disposed = true
        items.forEach { synchronized(it) { if (it.busy.get()) it.cancelled.set(true) } }
        // Drain queued workers so each executes cancellation cleanup.
        pool.shutdown()
    }
    private fun notice(message: String) { if (!activity.isFinishing) AlertDialog.Builder(activity).setTitle("Download Guard").setMessage(message).setPositiveButton("OK", null).show() }
}
