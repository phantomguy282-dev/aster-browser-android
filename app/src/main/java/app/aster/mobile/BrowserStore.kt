package app.aster.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BrowserStore(context: Context, private val ephemeral: Boolean = false) {
    data class Entry(val id: String, val url: String, val title: String, val time: Long, val folder: String = "")
    private val file = AtomicFile(File(context.filesDir, "browser.json"))
    val bookmarks = mutableListOf<Entry>()
    val history = mutableListOf<Entry>()
    val session = mutableListOf<String>()
    var theme = "dark"
    var search = "google"
    var home = ""
    var thirdPartyCookies = false
    var httpsFirst = true
    var shieldMode = "STANDARD"
    var threatProtection = true
    var adultFiltering = false
    var trackerProtection = true
    val domains = mutableMapOf<String, List<String>>()
    var exceptions = emptyList<String>()
    var warning = ""
    init {
        if (!ephemeral && file.baseFile.exists()) try {
            require(file.baseFile.length() <= 4 * 1024 * 1024) { "Profile too large" }
            val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            fun read(key: String, output: MutableList<Entry>, limit: Int) {
                val arr = data.optJSONArray(key) ?: return
                require(arr.length() <= limit)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.getString("url")
                    if (NavigationPolicy.isWebAddress(url)) output += Entry(o.optString("id", UUID.randomUUID().toString()), url, o.optString("title", url).take(300), o.optLong("time"), o.optString("folder").take(100))
                }
            }
            read("bookmarks", bookmarks, 2000); read("history", history, 2000)
            val tabs = data.optJSONArray("session") ?: JSONArray()
            for (i in 0 until minOf(12, tabs.length())) if (NavigationPolicy.isWebAddress(tabs.optString(i))) session += tabs.getString(i)
            theme = data.optString("theme", "dark").takeIf { it in listOf("dark", "light", "system") } ?: "dark"
            search = data.optString("search", "google").takeIf { it in listOf("google", "bing", "duckduckgo") } ?: "google"
            home = data.optString("home").takeIf { it.isBlank() || NavigationPolicy.isWebAddress(it) } ?: ""
            thirdPartyCookies = data.optBoolean("thirdPartyCookies", false); httpsFirst = data.optBoolean("httpsFirst", true)
            shieldMode = data.optString("shieldMode", "STANDARD").takeIf { it in MobileShield.Mode.entries.map { m -> m.name } } ?: "STANDARD"
            threatProtection = data.optBoolean("threatProtection", true); adultFiltering = data.optBoolean("adultFiltering", false); trackerProtection = data.optBoolean("trackerProtection", true)
            for (category in MobileShield.Category.entries) {
                val a = data.optJSONObject("domains")?.optJSONArray(category.name) ?: JSONArray()
                require(a.length() <= 10000)
                domains[category.name] = (0 until a.length()).map { MobileShield.domain(a.getString(it)) }
            }
            val a = data.optJSONArray("exceptions") ?: JSONArray(); require(a.length() <= 200)
            exceptions = (0 until a.length()).map { MobileShield.domain(a.getString(it)) }
        } catch (_: Exception) {
            bookmarks.clear(); history.clear(); session.clear()
            val backup = File(file.baseFile.parentFile, "browser-invalid-${System.currentTimeMillis()}.json")
            if (runCatching { file.baseFile.copyTo(backup, overwrite = false) }.isFailure) throw IllegalStateException("Profile needs recovery; original was not overwritten")
            warning = "Invalid profile preserved for recovery; loaded default settings."
        }
    }
    fun save() {
        if (ephemeral) return
        fun entries(items: List<Entry>) = JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("time", it.time).put("folder", it.folder)) } }
        val data = JSONObject().put("version", 1).put("bookmarks", entries(bookmarks.take(2000))).put("history", entries(history.take(2000)))
            .put("session", JSONArray(session.take(12))).put("theme", theme).put("search", search).put("home", home)
            .put("thirdPartyCookies", thirdPartyCookies).put("httpsFirst", httpsFirst).put("shieldMode", shieldMode)
            .put("threatProtection", threatProtection).put("adultFiltering", adultFiltering).put("trackerProtection", trackerProtection)
            .put("domains", JSONObject().apply { domains.forEach { (k, v) -> put(k, JSONArray(v)) } }).put("exceptions", JSONArray(exceptions))
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(data.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { if (stream != null) file.failWrite(stream); throw e }
    }
    fun visit(url: String, title: String) {
        if (ephemeral || !NavigationPolicy.isWebAddress(url)) return
        if (history.firstOrNull()?.url == url) return
        history.add(0, Entry(UUID.randomUUID().toString(), url, title.take(300), System.currentTimeMillis()))
        while (history.size > 2000) history.removeAt(history.lastIndex)
        save()
    }
    fun bookmark(url: String, title: String, folder: String) {
        require(!ephemeral) { "Bookmarks are disabled in this private session" }
        require(NavigationPolicy.isWebAddress(url) && bookmarks.size < 2000)
        if (bookmarks.none { it.url == url && it.folder == folder }) bookmarks += Entry(UUID.randomUUID().toString(), url, title.take(300), System.currentTimeMillis(), folder.take(100))
        save()
    }
}
