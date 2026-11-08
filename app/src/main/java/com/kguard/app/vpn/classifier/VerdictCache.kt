package com.kguard.app.vpn.classifier

import android.content.Context
import android.content.SharedPreferences
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Minimal key-value view over the cache's storage, so the cache logic can be unit tested
 * without Android. Production binds [SharedPreferencesVerdictStorage].
 */
interface VerdictStorage {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun put(values: Map<String, Any?>)
}

/** Production storage backed by the shared `"web_protection"` preferences file. */
class SharedPreferencesVerdictStorage(prefs: SharedPreferences) : VerdictStorage {
    private val prefs = prefs
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun put(values: Map<String, Any?>) {
        val e = prefs.edit()
        for ((k, v) in values) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            else -> e.remove(k)
        }
        e.apply()
    }
}

/** Test storage: an in-memory map with the same contract. */
class InMemoryVerdictStorage : VerdictStorage {
    val map = HashMap<String, Any?>()
    override fun getString(key: String, def: String?): String? = map[key] as String? ?: def
    override fun getInt(key: String, def: Int): Int = (map[key] as Int?) ?: def
    override fun put(values: Map<String, Any?>) { map.putAll(values) }
}

/**
 * Wraps a [DomainClassifier] with [VerdictCache] so a domain is judged once per generation,
 * not once per lookup. Cached `null` verdicts (benign passes) are cached too.
 */
class CachedDomainClassifier(
    private val delegate: DomainClassifier,
    private val cache: VerdictCache
) : DomainClassifier {
    override fun classify(domain: String, age: AgeProfile): ClassifierVerdict? {
        val key = domain.trim().trimEnd('.').lowercase()
        cache.get(key)?.let { return it }
        val verdict = delegate.classify(domain, age)
        cache.put(key, verdict)
        return verdict
    }
}

@Serializable
private data class CachedVerdict(
    val category: String,      // "" encodes a cached `null` (the classifier judged and passed the site)
    val confidence: Double,
    val riskScore: Double,
    val label: String,
    val expiry: Long
)

/**
 * Per-domain cache of classifier verdicts so a lookup costs nothing after the first visit.
 * Everything lives in one JSON blob; entries expire after [ttlMs] and the least recently used
 * are dropped past [capacity].
 *
 * Invalidation is generation-based: [VpnConfigStore] bumps `cache_gen` whenever the inputs a
 * verdict was judged against change (age profile, category rules, AI toggle). Every access
 * compares the stored generation with the one this cache was loaded under and self-clears on
 * mismatch — this works across service restarts and recreated filter instances, without the
 * cache needing a reference to the config.
 *
 * The cache holds only domain → verdict pairs with an expiry. It is not a browsing history.
 */
class VerdictCache(
    private val storage: VerdictStorage,
    private val ttlMs: Long = DEFAULT_TTL,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    private var loaded = false
    private val entries = LinkedHashMap<String, CachedVerdict>()

    /**
     * Reconciles this cache with the stored generation. `cache_gen` is the config's counter,
     * `cache_gen_seen` the generation this cache last observed. A mismatch means verdicts were
     * judged under different inputs (age profile, rules, AI toggle) — wipe everything. An equal
     * pair means business as usual: load the blob once, then keep memory authoritative.
     */
    private fun ensureCurrent() {
        val gen = storage.getInt(GEN_KEY, 0)
        val seen = storage.getInt(SEEN_KEY, 0)
        if (gen != seen) {
            synchronized(this) {
                entries.clear()
                storage.put(mapOf<String, Any?>(BLOB_KEY to null, SEEN_KEY to gen))
                loaded = true
            }
        } else if (!loaded) {
            synchronized(this) {
                entries.clear()
                fromJson(storage.getString(BLOB_KEY, null))?.let { entries.putAll(it) }
                loaded = true
            }
        }
    }

    /** @return the cached verdict, `null` (a real miss or a cached pass), for [domain]. */
    fun get(domain: String): ClassifierVerdict? {
        ensureCurrent()
        val key = key(domain)
        val hit: CachedVerdict? = synchronized(this) {
            val v = entries.remove(key) ?: return@synchronized null
            entries[key] = v // LRU touch
            v
        }
        if (hit == null) return null
        if (hit.expiry < System.currentTimeMillis()) {
            synchronized(this) { entries.remove(key); persist() }
            return null
        }
        return hit.toVerdict()
    }

    /** Caches [verdict]; `null` is cached too, so repeated benign lookups stay free. */
    fun put(domain: String, verdict: ClassifierVerdict?) {
        ensureCurrent()
        val key = key(domain)
        val entry = CachedVerdict(
            category = verdict?.category?.id ?: "",
            confidence = verdict?.confidence ?: 0.0,
            riskScore = verdict?.riskScore ?: 0.0,
            label = verdict?.label ?: "",
            expiry = System.currentTimeMillis() + ttlMs
        )
        synchronized(this) {
            entries.remove(key)
            entries[key] = entry
            while (entries.size > capacity) {
                val eldest = entries.keys.firstOrNull() ?: break // LinkedHashMap: eldest first
                entries.remove(eldest)
            }
            persist()
        }
    }

    /** Drops everything, including across processes that read the same prefs later. */
    fun clear() {
        synchronized(this) {
            entries.clear()
            storage.put(mapOf<String, Any?>(BLOB_KEY to null))
        }
    }

    private fun persist() {
        storage.put(mapOf(BLOB_KEY to toJson(entries)))
    }

    private fun CachedVerdict.toVerdict(): ClassifierVerdict? =
        if (category.isEmpty()) null
        else SafetyCategory.entries.find { it.id == category }?.let {
            ClassifierVerdict(it, confidence, riskScore, label)
        }

    private fun key(domain: String) = domain.trim().trimEnd('.').lowercase()

    private val blobSerializer = MapSerializer(String.serializer(), CachedVerdict.serializer())

    private fun toJson(map: Map<String, CachedVerdict>): String = json.encodeToString(blobSerializer, map)
    private fun fromJson(text: String?): Map<String, CachedVerdict>? =
        text?.let { runCatching { json.decodeFromString(blobSerializer, it) }.getOrNull() }

    companion object {
        internal const val GEN_KEY = "cache_gen"
        private const val SEEN_KEY = "cache_gen_seen"
        private const val BLOB_KEY = "classifier_cache"
        const val DEFAULT_TTL = 24L * 60 * 60 * 1000
        const val DEFAULT_CAPACITY = 4_096
        private val json = Json { ignoreUnknownKeys = true }

        /** Production binding: same prefs file the VPN config already uses. */
        fun from(context: Context) = VerdictCache(
            SharedPreferencesVerdictStorage(
                context.applicationContext.getSharedPreferences("web_protection", Context.MODE_PRIVATE)
            )
        )
    }
}
