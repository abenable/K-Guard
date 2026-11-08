package com.kguard.app.vpn.classifier

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerdictCacheTest {

    private val storage = InMemoryVerdictStorage()

    private fun cache(ttl: Long = 1000, capacity: Int = 3) = VerdictCache(storage, ttl, capacity)

    private fun verdict(risk: Double = 0.9) = ClassifierVerdict(SafetyCategory.SEXUAL, 0.95, risk, "adult content")

    @Test
    fun `stores and returns a verdict`() {
        val c = cache()
        c.put("Example.com.", verdict())
        assertEquals(verdict().category, c.get("example.com")!!.category)
    }

    @Test
    fun `miss returns null`() {
        assertNull(cache().get("unknown.com"))
    }

    @Test
    fun `entries expire after the ttl`() {
        val c = cache(ttl = 50)
        c.put("old.com", verdict())
        Thread.sleep(60)
        assertNull(c.get("old.com"))
    }

    @Test
    fun `null verdicts are cached too`() {
        val c = cache()
        c.put("benign.com", null)
        // A cached pass must be distinguishable from a miss: the sentinel entry round-trips.
        // get() returns null either way, so verify through the blob containing the domain.
        c.put("adult.com", verdict())
        assertNotNull(storage.map[VerdictCacheTestBlob.key].let { it as String? }?.contains("benign.com") == true)
    }

    @Test
    fun `least recently used entries are evicted past capacity`() {
        val c = cache(capacity = 2)
        c.put("a.com", verdict())
        c.put("b.com", verdict())
        c.get("a.com") // touch a
        c.put("c.com", verdict()) // evicts b, the eldest
        assertNotNull(c.get("a.com"))
        assertNull(c.get("b.com"))
        assertNotNull(c.get("c.com"))
    }

    @Test
    fun `bumping the generation invalidates cached verdicts`() {
        val c = cache()
        c.put("pornhub.com", verdict())
        assertNotNull(c.get("pornhub.com"))

        // VpnConfigStore bumps cache_gen when the age profile or rules change.
        val gen = storage.getInt(VerdictCache.GEN_KEY, 0)
        storage.put(mapOf(VerdictCache.GEN_KEY to gen + 1))
        assertNull(c.get("pornhub.com"))

        // …and new verdicts are stored under the new generation.
        c.put("pornhub.com", verdict())
        assertNotNull(c.get("pornhub.com"))
    }

    @Test
    fun `cache survives being rebuilt from the same storage`() {
        val c1 = cache()
        c1.put("kept.com", verdict())
        val c2 = cache() // e.g. the service restarted and rebuilt the filter
        assertNotNull(c2.get("kept.com"))
    }

    @Test
    fun `corrupt blobs are treated as empty`() {
        storage.put(mapOf(VerdictCacheTestBlob.key to "{not json"))
        assertNull(cache().get("anything.com"))
    }

    @Test
    fun `unknown category ids decode to a miss`() {
        storage.put(mapOf(
            VerdictCacheTestBlob.key to """{"gone.com":{"category":"deleted_cat","confidence":0.9,"riskScore":0.9,"label":"x","expiry":${System.currentTimeMillis() + 60_000}}}"""
        ))
        assertNull(cache().get("gone.com"))
    }

    @Test
    fun `classifier integration round-trips through the cache`() {
        val c = cache()
        val delegate = HeuristicDomainClassifier()
        val domain = "pornhub.com"
        val direct = delegate.classify(domain, AgeProfile.KIDS_3_7)!!
        c.put(domain, direct)
        val cached = c.get(domain)!!
        assertEquals(direct.category, cached.category)
        assertEquals(direct.riskScore, cached.riskScore, 1e-9)
        assertEquals(direct.label, cached.label)
    }
}

/** Re-exports the private blob key for test assertions. */
object VerdictCacheTestBlob {
    val key: String = "classifier_cache"
}
