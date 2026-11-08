package com.kguard.app.vpn

import android.content.Context
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.vpn.classifier.VerdictCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnConfig(
    val enabled: Boolean = false,
    val childId: String? = null,
    /** Set on a paired child's phone, where the parent's settings arrive from Firestore. */
    val familyId: String? = null,
    val policy: VpnPolicy = VpnPolicy(AgeProfile.KIDS_3_7) // strictest until a child is chosen
)

/** Keeps the filter settings where the service can read them without a network or Firestore connection. */
class VpnConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("web_protection", Context.MODE_PRIVATE)

    init {
        // Every instance shares one set of state flows (see Shared below); hydrate them from prefs once.
        Shared.initFrom(prefs)
    }

    fun load(): VpnConfig = VpnConfig(
        enabled = prefs.getBoolean("enabled", false),
        childId = prefs.getString("childId", null),
        familyId = prefs.getString("familyId", null),
        policy = VpnPolicy(
            ageProfile = AgeProfile.fromId(prefs.getString("age", AgeProfile.KIDS_3_7.id)),
            rules = (prefs.getString("rules", "") ?: "").split(',').mapNotNull {
                val p = it.split('='); if (p.size == 2) p[0] to p[1] else null
            }.toMap(),
            customBlock = prefs.getStringSet("customBlock", emptySet()) ?: emptySet(),
            customAllow = prefs.getStringSet("customAllow", emptySet()) ?: emptySet(),
            educationalAllow = prefs.getBoolean("edu", true),
            aiFilterEnabled = prefs.getBoolean("ai", true)
        )
    )

    /** Copies the child's age group and category rules; custom domain lists are kept. */
    fun applyChild(child: ChildProfile) {
        prefs.edit()
            .putString("childId", child.id)
            .putString("age", child.ageProfile)
            .putString("rules", child.categoryRules.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putInt(VerdictCache.GEN_KEY, prefs.getInt(VerdictCache.GEN_KEY, 0) + 1)
            .apply()
        Shared._customBlock.value = load().policy.customBlock
    }

    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean("enabled", enabled).apply() }

    /**
     * Child's phone: replaces the whole policy with what the parent set. The VPN service reads this store on
     * every DNS lookup, so the change applies to the next lookup without restarting the tunnel. Cached AI
     * verdicts are invalidated when the age group, rules or AI switch change.
     */
    fun applyRemote(familyId: String, childId: String, ageProfile: String, rules: Map<String, String>, settings: WebSettings) {
        val rulesStr = rules.entries.joinToString(",") { "${it.key}=${it.value}" }
        val verdictsStale = prefs.getString("age", null) != ageProfile ||
            prefs.getString("rules", "") != rulesStr ||
            prefs.getBoolean("ai", true) != settings.aiFilter
        val edit = prefs.edit()
            .putString("familyId", familyId)
            .putString("childId", childId)
            .putString("age", ageProfile)
            .putString("rules", rulesStr)
            .putStringSet("customBlock", settings.customBlock)
            .putStringSet("customAllow", settings.customAllow)
            .putBoolean("edu", settings.educationalAllow)
            .putBoolean("ai", settings.aiFilter)
        if (verdictsStale) edit.putInt(VerdictCache.GEN_KEY, prefs.getInt(VerdictCache.GEN_KEY, 0) + 1)
        edit.apply()
        Shared._customBlock.value = settings.customBlock
        Shared._customAllow.value = settings.customAllow
        Shared._educational.value = settings.educationalAllow
        Shared._aiFilter.value = settings.aiFilter
    }

    fun addCustomBlock(domain: String): Boolean {
        val d = cleanDomain(domain) ?: return false
        val next = (load().policy.customBlock + d)
        prefs.edit().putStringSet("customBlock", next).putStringSet("customAllow", load().policy.customAllow - d).apply()
        Shared._customBlock.value = next
        Shared._customAllow.value = load().policy.customAllow
        return true
    }

    fun addCustomAllow(domain: String): Boolean {
        val d = cleanDomain(domain) ?: return false
        val next = (load().policy.customAllow + d)
        prefs.edit().putStringSet("customAllow", next).putStringSet("customBlock", load().policy.customBlock - d).apply()
        Shared._customAllow.value = next
        Shared._customBlock.value = load().policy.customBlock
        return true
    }

    fun removeCustomAllow(domain: String) {
        val d = cleanDomain(domain) ?: return // an invalid input can never match a stored, valid domain
        val next = load().policy.customAllow - d
        prefs.edit().putStringSet("customAllow", next).apply()
        Shared._customAllow.value = next
    }

    fun setEducationalAllow(on: Boolean) {
        prefs.edit().putBoolean("edu", on).apply()
        Shared._educational.value = on
    }

    /**
     * Turning the AI filter on or off (or changing the child it filters for) invalidates every
     * cached classifier verdict, because verdicts are judged against this configuration.
     */
    fun setAiFilter(on: Boolean) {
        prefs.edit().putBoolean("ai", on).putInt(VerdictCache.GEN_KEY, prefs.getInt(VerdictCache.GEN_KEY, 0) + 1).apply()
        Shared._aiFilter.value = on
    }

    fun removeCustomBlock(domain: String) {
        val d = cleanDomain(domain) ?: return // an invalid input can never match a stored, valid domain
        val next = load().policy.customBlock - d
        prefs.edit().putStringSet("customBlock", next).apply()
        Shared._customBlock.value = next
    }

    val customBlock: StateFlow<Set<String>> get() = Shared._customBlock
    val customAllow: StateFlow<Set<String>> get() = Shared._customAllow
    val educationalAllow: StateFlow<Boolean> get() = Shared._educational
    val aiFilter: StateFlow<Boolean> get() = Shared._aiFilter

    /**
     * One shared, app-wide state for every VpnConfigStore instance: the service and the settings
     * screens must observe the same values, not per-instance copies that drift apart.
     */
    companion object Shared {
        /** Turns "https://www.Example.com/page" into "example.com"; null if it is not a website name. */
        fun cleanDomain(raw: String): String? {
            // Keyboards often insert spaces after dots ("example. com"); web addresses never contain spaces.
            val compact = raw.filterNot { it.isWhitespace() }.lowercase()
            val d = DomainFilter.normalise(compact.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/'))
            return d.takeIf { DOMAIN.matches(it) }
        }

        internal val _customBlock = MutableStateFlow<Set<String>>(emptySet())
        internal val _customAllow = MutableStateFlow<Set<String>>(emptySet())
        internal val _educational = MutableStateFlow(true)
        internal val _aiFilter = MutableStateFlow(true)

        internal fun initFrom(prefs: android.content.SharedPreferences) {
            synchronized(this) {
                _customBlock.value = prefs.getStringSet("customBlock", emptySet()) ?: emptySet()
                _customAllow.value = prefs.getStringSet("customAllow", emptySet()) ?: emptySet()
                _educational.value = prefs.getBoolean("edu", true)
                _aiFilter.value = prefs.getBoolean("ai", true)
            }
        }

        private val DOMAIN = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$")
    }
}

data class BlockedEntry(val domain: String, val label: String, val explanation: String, val time: Long)

/** Live status shown in the app. Recent domains are kept in memory only and never written to disk. */
object VpnState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val _recent = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val recent: StateFlow<List<BlockedEntry>> = _recent.asStateFlow()

    fun setRunning(v: Boolean) { _running.value = v }
    fun recordBlock(domain: String, label: String, explanation: String) {
        _blockedCount.value += 1
        _recent.value = (listOf(BlockedEntry(domain, label, explanation, System.currentTimeMillis())) + _recent.value).take(20)
    }
}
