package com.kguard.app.vpn.classifier

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicDomainClassifierTest {

    private val classifier = HeuristicDomainClassifier()

    // -- Success criterion 1: unlisted adult domains block for children ------------------

    @Test
    fun `dedicated adult domains are classified for a young child`() {
        val ages = listOf(AgeProfile.KIDS_3_7, AgeProfile.PRETEENS_8_12, AgeProfile.TEENS_13_17)
        for (domain in listOf("pornhub.com", "xvideos.com", "redtube.com", "onlyfans.com",
                "chaturbate.com", "xhamster.com", "xxx-pics.net", "hentai.to")) {
            val v = classifier.classify(domain, AgeProfile.KIDS_3_7)
            assertNotNull("$domain must be judged for kids", v)
            assertEquals(SafetyCategory.SEXUAL, v!!.category)
            for (age in ages) {
                val a = classifier.classify(domain, age)
                assertNotNull("$domain must be judged for $age", a)
                assertTrue("$domain risk must clear ${age.id} threshold", a!!.riskScore >= HeuristicDomainClassifier.threshold(age))
            }
        }
    }

    @Test
    fun `adult profiles are not blocked by adult content by default`() {
        for (domain in listOf("pornhub.com", "xvideos.com", "onlyfans.com")) {
            val v = classifier.classify(domain, AgeProfile.ADULTS_18_PLUS)
            assertTrue("$domain should stay under the adult threshold", v == null || v.riskScore < HeuristicDomainClassifier.threshold(AgeProfile.ADULTS_18_PLUS))
        }
    }

    @Test
    fun `scam domains classify and clear every age threshold`() {
        for (domain in listOf("freerobux-generator.com", "getrich-now.net", "winprize-cash.xyz")) {
            val v = classifier.classify(domain, AgeProfile.ADULTS_18_PLUS)
            assertNotNull("$domain must be judged", v)
            assertEquals(SafetyCategory.SCAM, v!!.category)
            assertTrue(v.riskScore >= HeuristicDomainClassifier.threshold(AgeProfile.ADULTS_18_PLUS))
        }
    }

    // -- Success criterion 2: benign unknown domains stay allowed ------------------------

    @Test
    fun `benign domains get no verdict`() {
        for (domain in listOf("khanacademy.org", "duolingo.com", "bbc.co.uk", "wikipedia.org",
                "stackoverflow.com", "github.com", "nationalgeographic.com")) {
            assertNull("$domain must not be flagged", classifier.classify(domain, AgeProfile.KIDS_3_7))
        }
    }

    @Test
    fun `benign vocabulary neutralises weak keyword hits`() {
        // "sex" appears, but the educational framing is unmistakable.
        assertNull(classifier.classify("sex-education-course.school.edu", AgeProfile.KIDS_3_7))
        assertNull(classifier.classify("safety-learning.sexual-health.org", AgeProfile.KIDS_3_7))
    }

    @Test
    fun `gibberish domains are not judged`() {
        for (domain in listOf("", "q.com", "zqxkwv.org", "a1b2c3.example")) {
            assertNull(classifier.classify(domain, AgeProfile.PRETEENS_8_12))
        }
    }

    // -- Signals -------------------------------------------------------------------------

    @Test
    fun `punycode domains raise a scam verdict`() {
        val v = classifier.classify("xn--80ak6aa92e.com", AgeProfile.KIDS_3_7)
        assertNotNull("punycode must be judged", v)
        assertEquals(SafetyCategory.SCAM, v!!.category)
    }

    @Test
    fun `same domain judged with different ages keeps the same category`() {
        val kid = classifier.classify("weed-shop.example", AgeProfile.KIDS_3_7)
        val teen = classifier.classify("weed-shop.example", AgeProfile.TEENS_13_17)
        assertEquals(kid!!.category, teen!!.category)
        assertTrue(kid.riskScore >= HeuristicDomainClassifier.threshold(AgeProfile.KIDS_3_7))
        assertTrue(teen.riskScore >= HeuristicDomainClassifier.threshold(AgeProfile.TEENS_13_17))
    }

    @Test
    fun `confidence never reaches a full certainty`() {
        val v = classifier.classify("pornhub.com", AgeProfile.KIDS_3_7)!!
        assertTrue(v.confidence in 0.80..0.99)
        assertFalse(v.label.isBlank())
    }
}
