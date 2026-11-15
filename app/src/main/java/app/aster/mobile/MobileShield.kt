package app.aster.mobile

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Local, user-supplied domain rules. Empty rules never imply reputation coverage. */
class MobileShield {
    enum class Mode { STANDARD, ENHANCED, STRICT }
    enum class Category { THREAT, ADULT, TRACKER }
    data class Decision(val blocked: Boolean, val category: Category? = null, val matchedDomain: String? = null)
    @Volatile var mode = Mode.STANDARD
    @Volatile var threatProtection = true
    @Volatile var adultFiltering = false
    @Volatile var trackerProtection = true
    @Volatile private var rules: Map<Category, Set<String>> = Category.entries.associateWith { emptySet() }
    @Volatile private var exceptions: Set<String> = emptySet()

    fun replace(category: Category, values: Collection<String>) {
        require(values.size <= 100000) { "Domain list exceeds 100,000 entries" }
        val parsed = values.map { domain(it) }.toSet()
        rules = rules + (category to parsed)
    }
    fun setExceptions(values: Collection<String>) {
        require(values.size <= 200) { "Exception limit exceeded" }
        exceptions = values.map { domain(it) }.toSet()
    }
    fun count(category: Category) = rules.getValue(category).size
    fun exceptionDomains() = exceptions.toList().sorted()
    fun domains(category: Category) = rules.getValue(category).toList().sorted()
    fun decide(url: String, mainFrame: Boolean): Decision {
        val host = runCatching { URI(NavigationPolicy.webAddress(url)).host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT) }.getOrNull() ?: return Decision(false)
        if (exceptions.any { matches(host, it) }) return Decision(false)
        val categories = buildList {
            if (threatProtection) add(Category.THREAT)
            if (adultFiltering) add(Category.ADULT)
            if (trackerProtection && mode != Mode.STANDARD && (!mainFrame || mode == Mode.STRICT)) add(Category.TRACKER)
        }
        for (category in categories) {
            val categoryRules = rules.getValue(category)
            var candidate = host
            var match: String? = null
            while (candidate.isNotEmpty()) {
                if (candidate in categoryRules) { match = candidate; break }
                val dot = candidate.indexOf('.')
                if (dot < 0) break
                candidate = candidate.substring(dot + 1)
            }
            if (match != null) return Decision(true, category, match)
        }
        return Decision(false)
    }
    companion object {
        fun matches(host: String, rule: String) = host == rule || host.endsWith(".$rule")
        fun domain(raw: String): String {
            val clean = raw.trim().removeSuffix(".")
            require(clean.isNotEmpty() && clean.length <= 253 && clean.none { it.isWhitespace() || it in "/:@?#\\%*" }) { "Enter plain domain names only" }
            val ascii = IDN.toASCII(clean, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(ascii.split('.').all { it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') }) { "Invalid domain" }
            return ascii
        }
    }
}
