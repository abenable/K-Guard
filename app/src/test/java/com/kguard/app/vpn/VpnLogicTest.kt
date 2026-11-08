package com.kguard.app.vpn

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLogicTest {

    private val filter = DomainFilter(
        mapOf(
            SafetyCategory.SEXUAL to setOf("adult.example"),
            SafetyCategory.VIOLENCE to setOf("gore.example"),
            SafetyCategory.SCAM to setOf("scam.example")
        )
    )

    private fun policy(age: AgeProfile, rules: Map<String, String> = emptyMap(), block: Set<String> = emptySet(), allow: Set<String> = emptySet()) =
        VpnPolicy(age, rules, block, allow)

    @Test fun subdomainsMatchButLookalikesDoNot() {
        val p = policy(AgeProfile.KIDS_3_7)
        assertTrue(filter.evaluate("www.adult.example", p).blocked)
        assertTrue(filter.evaluate("ADULT.example.", p).blocked)
        assertFalse(filter.evaluate("notadult.example", p).blocked)
        assertFalse(filter.evaluate("example", p).blocked)
    }

    @Test fun defaultsDependOnAge() {
        assertTrue(filter.evaluate("gore.example", policy(AgeProfile.PRETEENS_8_12)).blocked)
        assertTrue(filter.evaluate("gore.example", policy(AgeProfile.TEENS_13_17)).blocked)
        assertFalse(filter.evaluate("gore.example", policy(AgeProfile.ADULTS_18_PLUS)).blocked)
        assertTrue(filter.evaluate("scam.example", policy(AgeProfile.ADULTS_18_PLUS)).blocked)
    }

    @Test fun parentCanAllowViolenceButNotSexualForMinors() {
        val rules = mapOf("violence" to "allow", "sexual" to "allow")
        assertFalse(filter.evaluate("gore.example", policy(AgeProfile.TEENS_13_17, rules)).blocked)
        assertTrue(filter.evaluate("adult.example", policy(AgeProfile.TEENS_13_17, rules)).blocked)
        assertFalse(filter.evaluate("adult.example", policy(AgeProfile.ADULTS_18_PLUS, rules)).blocked)
    }

    @Test fun blockRuleTightensAdultProfile() {
        val v = filter.evaluate("gore.example", policy(AgeProfile.ADULTS_18_PLUS, mapOf("violence" to "block")))
        assertTrue(v.blocked); assertEquals(SafetyCategory.VIOLENCE, v.category)
    }

    @Test fun customListsWinOverCategories() {
        val p = policy(AgeProfile.KIDS_3_7, block = setOf("games.example"), allow = setOf("adult.example"))
        assertTrue(filter.evaluate("games.example", p).custom)
        assertFalse(filter.evaluate("adult.example", p).blocked)
    }

    @Test fun parsesListsWithComments() {
        assertEquals(setOf("a.com", "b.org"), DomainFilter.parseList("# hi\nA.com\n\nb.org # note\n"))
    }

    @Test fun wildcardAndDotEntriesMatchTheBareDomainAndSubdomains() {
        val set = DomainFilter.parseList("*.example.com\n.leader.net\nplain.org\n")
        assertEquals(setOf("example.com", "leader.net", "plain.org"), set)
        assertTrue(DomainFilter.matches("example.com", set))
        assertTrue(DomainFilter.matches("a.b.example.com", set))
        assertTrue(DomainFilter.matches("x.leader.net", set))
        assertTrue(DomainFilter.matches("leader.net", set))
        assertFalse(DomainFilter.matches("notexample.com", set))
    }

    @Test fun aDomainInSeveralCategoriesIsBlockedIfAnyOfThemBlocks() {
        val multi = DomainFilter(
            mapOf(
                SafetyCategory.VIOLENCE to setOf("both.example"),
                SafetyCategory.SEXUAL to setOf("both.example")
            )
        )
        // The first list allows it (parent rule), the second blocks by age: the old first-match
        // logic would have allowed it.
        val teen = VpnPolicy(AgeProfile.TEENS_13_17, rules = mapOf("violence" to "allow"))
        val v = multi.evaluate("both.example", teen)
        assertTrue(v.blocked)
        assertEquals(SafetyCategory.SEXUAL, v.category)
        assertEquals(Reason.BLOCKED_AGE, v.reason)
    }

    @Test fun lockedBeatsParentRuleWhenSeveralCategoriesMatch() {
        val multi = DomainFilter(
            mapOf(
                SafetyCategory.VIOLENCE to setOf("both.example"),
                SafetyCategory.SEXUAL to setOf("both.example")
            )
        )
        val teen = VpnPolicy(AgeProfile.TEENS_13_17, rules = mapOf("violence" to "allow", "sexual" to "allow"))
        val v = multi.evaluate("both.example", teen)
        assertTrue(v.blocked)
        assertEquals(Reason.BLOCKED_LOCKED, v.reason)
    }

    @Test fun allowedVerdictsUseTheStrongestAuthorityAmongMatchingLists() {
        val multi = DomainFilter(
            mapOf(
                SafetyCategory.DRUGS to setOf("both.example"),
                SafetyCategory.VIOLENCE to setOf("both.example")
            )
        )
        // For adults both categories allow, but only violence carries a parent rule: that reason wins.
        val adult = VpnPolicy(AgeProfile.ADULTS_18_PLUS, rules = mapOf("violence" to "allow"))
        val v = multi.evaluate("both.example", adult)
        assertFalse(v.blocked)
        assertEquals(SafetyCategory.VIOLENCE, v.category)
        assertEquals(Reason.ALLOWED_BY_RULE, v.reason)
    }

    @Test fun aListedSiteAllowedByAgeIsExplainedHonestly() {
        val adult = VpnPolicy(AgeProfile.ADULTS_18_PLUS)
        val v = filter.evaluate("gore.example", adult)
        assertFalse(v.blocked)
        assertEquals(Reason.ALLOWED_LISTED, v.reason)
        val why = DomainFilter.explain(v, adult)
        assertTrue("mentions the list", why.contains("list"))
        assertFalse("must not claim the site was not found", why.contains("nothing found"))
        assertEquals(Reason.ALLOWED, filter.evaluate("unknown.example", adult).reason) // unlisted stays plain
    }

    private fun query(name: String): ByteArray {
        val head = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)
        val q = name.split('.').flatMap { l -> listOf(l.length.toByte()) + l.map { it.code.toByte() } } + listOf<Byte>(0, 0, 1, 0, 1)
        return head + q.toByteArray()
    }

    @Test fun parsesDnsNameAndBuildsNxDomain() {
        val dns = query("Www.Adult.Example")
        assertEquals("www.adult.example", DnsPacket.parseName(dns))
        val nx = DnsPacket.nxDomain(dns)
        assertEquals(0x83, nx[3].toInt() and 0xff)
        assertTrue(nx[2].toInt() and 0x80 != 0)
        assertArrayEquals(byteArrayOf(0x12, 0x34), nx.copyOfRange(0, 2))
        assertEquals(dns.size, nx.size)
    }

    @Test fun roundTripsThroughIpv4Packet() {
        val dns = query("adult.example")
        val pkt = DnsPacket.wrapUdp(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1), 40000, 53, dns)
        val q = DnsPacket.parseQuery(pkt, pkt.size)
        assertNotNull(q)
        assertEquals("adult.example", q!!.name)
        assertEquals(40000, q.srcPort)
        val reply = DnsPacket.reply(q, DnsPacket.nxDomain(dns))
        val back = DnsPacket.parseQuery(reply, reply.size)
        assertNull(back) // reply goes to port 40000, not 53, so it is not treated as a query
        assertEquals(0, DnsPacket.ipChecksum(reply, 20).let { c -> // checksum over a valid header is zero when included
            var sum = 0; var i = 0
            while (i < 20) { sum += ((reply[i].toInt() and 0xff) shl 8) or (reply[i + 1].toInt() and 0xff); i += 2 }
            while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
            (sum.inv() and 0xffff)
        })
    }

    @Test fun ignoresNonDnsAndMalformedPackets() {
        assertNull(DnsPacket.parseQuery(ByteArray(10), 10))
        val tcpLike = DnsPacket.wrapUdp(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1), 1, 80, query("a.com"))
        assertNull(DnsPacket.parseQuery(tcpLike, tcpLike.size))
        assertNull(DnsPacket.parseName(ByteArray(5)))
    }

    @Test fun queriesFromPort53AreRejected() {
        val spoofed = DnsPacket.wrapUdp(byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 1), 53, 53, query("a.com"))
        assertNull(DnsPacket.parseQuery(spoofed, spoofed.size))
    }

    @Test fun servFailMarksAnErrorWithNoAnswers() {
        val q = query("example.com")
        val f = DnsPacket.servFail(q)
        assertEquals("RCODE 2", 2, f[3].toInt() and 0x0f)
        assertEquals(0, u16(f, 6))
        assertArrayEquals(q.copyOfRange(0, 2), f.copyOfRange(0, 2)) // the client's id is echoed
    }

    @Test fun builtQueriesCarryARandomSixteenBitId() {
        val queries = (1..10).map { DnsPacket.buildQuery("a.com", 1) }
        queries.forEach { q ->
            val id = u16(q, 0)
            assertTrue("id fits 16 bits", id in 0..0xFFFF)
        }
        assertTrue("ids vary between queries", queries.map { it[0] to it[1] }.distinct().size > 1)
    }

    @Test fun roundTripsThroughAnIpv6Packet() {
        val dns = query("adult.example")
        val src = ByteArray(16) { it.toByte() }
        val dst = ByteArray(16) { (it + 100).toByte() }
        val pkt = DnsPacket.wrapUdp6(src, dst, 40000, 53, dns)
        val q = DnsPacket.parseQuery(pkt, pkt.size)
        assertNotNull(q)
        assertArrayEquals(src, q!!.srcIp)
        assertArrayEquals(dst, q.dstIp)
        assertEquals(40000, q.srcPort)
        assertEquals("adult.example", q.name)
        // The UDP checksum over the pseudo-header plus the finished datagram must total 0xFFFF.
        var sum = 0
        for (i in 8 until 40) sum += u16(pkt, i)
        val len = pkt.size - 48
        sum += (len shr 16) and 0xffff
        sum += len and 0xffff
        sum += 17
        sum += u16(pkt, 40) + u16(pkt, 42) + u16(pkt, 44)
        sum += u16(pkt, 46)                             // the checksum word itself completes the invariant
        var i = 48
        while (i + 1 < pkt.size) { sum += u16(pkt, i); i += 2 }
        if (i < pkt.size) sum += (pkt[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        assertEquals(0xFFFF, sum)
        val reply = DnsPacket.reply(q, DnsPacket.nxDomain(dns))
        assertEquals(6, reply[0].toInt() shr 4)                 // still IPv6 on the way back
        assertArrayEquals(dst, reply.copyOfRange(8, 24))        // addresses are swapped
        assertArrayEquals(src, reply.copyOfRange(24, 40))
        assertNull(DnsPacket.parseQuery(reply, reply.size))     // a reply (dst port 40000) is not a query
    }

    @Test fun ipv6NonDnsTrafficIsIgnored() {
        val other = DnsPacket.wrapUdp6(ByteArray(16), ByteArray(16), 5353, 80, query("a.com"))
        assertNull(DnsPacket.parseQuery(other, other.size))
        val tcp6 = DnsPacket.wrapUdp6(ByteArray(16), ByteArray(16), 40000, 53, query("a.com")).also { it[6] = 6 } // next header TCP
        assertNull(DnsPacket.parseQuery(tcp6, tcp6.size))
        assertNull(DnsPacket.parseQuery(ByteArray(30) { 0x60.toByte() }, 30)) // too short for v6+UDP
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
}

class VpnExplainTest {
    private val filter = DomainFilter(
        mapOf(SafetyCategory.SEXUAL to setOf("adult.example", "wikipedia.org"), SafetyCategory.VIOLENCE to setOf("gore.example")),
        educational = setOf("wikipedia.org")
    )

    @Test fun educationalSiteIsAllowedEvenIfListed() {
        val p = VpnPolicy(AgeProfile.KIDS_3_7)
        val v = filter.evaluate("en.wikipedia.org", p)
        assertFalse(v.blocked); assertEquals(Reason.ALLOWED_EDUCATIONAL, v.reason)
        assertTrue(filter.evaluate("en.wikipedia.org", p.copy(educationalAllow = false)).blocked)
    }

    @Test fun reasonsAndExplanations() {
        val kid = VpnPolicy(AgeProfile.PRETEENS_8_12)
        val v = filter.evaluate("gore.example", kid)
        assertEquals(Reason.BLOCKED_AGE, v.reason)
        assertTrue(DomainFilter.explain(v, kid).contains("ages 8"))
        val teenAllow = VpnPolicy(AgeProfile.TEENS_13_17, rules = mapOf("sexual" to "allow", "violence" to "allow"))
        assertEquals(Reason.BLOCKED_LOCKED, filter.evaluate("adult.example", teenAllow).reason)
        assertEquals(Reason.ALLOWED_BY_RULE, filter.evaluate("gore.example", teenAllow).reason)
        assertEquals(Reason.BLOCKED_RULE, filter.evaluate("gore.example", VpnPolicy(AgeProfile.ADULTS_18_PLUS, mapOf("violence" to "block"))).reason)
    }

    @Test fun effectiveMatrixMatchesDefaults() {
        assertTrue(DomainFilter.effective(SafetyCategory.DRUGS, VpnPolicy(AgeProfile.TEENS_13_17)).blocked)
        assertFalse(DomainFilter.effective(SafetyCategory.PROFANITY, VpnPolicy(AgeProfile.TEENS_13_17)).blocked)
        assertEquals(Source.RULE, DomainFilter.effective(SafetyCategory.DRUGS, VpnPolicy(AgeProfile.TEENS_13_17, mapOf("drugs" to "allow"))).source)
    }
}

class VpnAiFilterTest {
    // The real v0 classifier is pure Kotlin, so the filter is tested end to end without Android.
    private val filter = DomainFilter(
        mapOf(SafetyCategory.SEXUAL to setOf("adult.example")),
        classifier = com.kguard.app.vpn.classifier.HeuristicDomainClassifier()
    )

    private fun policy(age: AgeProfile, rules: Map<String, String> = emptyMap(),
                       block: Set<String> = emptySet(), allow: Set<String> = emptySet(),
                       ai: Boolean = true) =
        VpnPolicy(age, rules, block, allow, aiFilterEnabled = ai)

    @Test fun unlistedAdultDomainIsBlockedByTheClassifierForChildren() {
        for (age in listOf(AgeProfile.KIDS_3_7, AgeProfile.PRETEENS_8_12, AgeProfile.TEENS_13_17)) {
            val v = filter.evaluate("pornhub.com", policy(age))
            assertTrue("pornhub.com must be blocked for ${age.id}", v.blocked)
            assertEquals(Reason.BLOCKED_AI, v.reason)
            assertEquals(SafetyCategory.SEXUAL, v.category)
        }
    }

    @Test fun theAiVerdictRespectsTheUnder18Lock() {
        // A parent allowed sexual content, but for minors that rule is locked: the AI block stands.
        val v = filter.evaluate("pornhub.com", policy(AgeProfile.KIDS_3_7, rules = mapOf("sexual" to "allow")))
        assertTrue(v.blocked)
        assertEquals(Reason.BLOCKED_AI, v.reason)
    }

    @Test fun aParentAllowlistEntryStillBeatsTheClassifier() {
        val v = filter.evaluate("pornhub.com", policy(AgeProfile.KIDS_3_7, allow = setOf("pornhub.com")))
        assertFalse(v.blocked)
        assertEquals(Reason.ALLOWED_PARENT, v.reason)
    }

    @Test fun turningTheAiFilterOffRestoresListOnlyBehaviour() {
        val v = filter.evaluate("pornhub.com", policy(AgeProfile.KIDS_3_7, ai = false))
        assertFalse(v.blocked)
        assertEquals(Reason.ALLOWED, v.reason)
    }

    @Test fun aJudgedButUnderThresholdDomainIsAllowedWithAnAiReason() {
        // Scam signals, but judged for an adult: verdict exists, risk is under the adult threshold.
        val adult = policy(AgeProfile.ADULTS_18_PLUS)
        val v = filter.evaluate("hack-giveaway.example", adult)
        assertFalse(v.blocked)
        assertEquals(Reason.ALLOWED_AI, v.reason)
    }

    @Test fun benignDomainsWithoutAVerdictStayPlainlyAllowed() {
        val v = filter.evaluate("khanacademy.org", policy(AgeProfile.KIDS_3_7))
        assertFalse(v.blocked)
        assertEquals(Reason.ALLOWED, v.reason)
    }

    @Test fun aiExplanationsMentionTheFilter() {
        val kid = policy(AgeProfile.KIDS_3_7)
        val blocked = filter.evaluate("pornhub.com", kid)
        assertTrue(DomainFilter.explain(blocked, kid).contains("AI filter"))
        val allowed = filter.evaluate("hack-giveaway.example", policy(AgeProfile.ADULTS_18_PLUS))
        assertTrue(DomainFilter.explain(allowed, policy(AgeProfile.ADULTS_18_PLUS)).contains("AI filter"))
    }

    @Test fun dohResolversAreHardBlocked() {
        val f = DomainFilter(
            emptyMap(),
            doh = setOf("cloudflare-dns.com", "dns.google"),
            classifier = com.kguard.app.vpn.classifier.HeuristicDomainClassifier()
        )
        val p = policy(AgeProfile.KIDS_3_7, allow = setOf("cloudflare-dns.com")) // even a parent allow
        val v = f.evaluate("dns.cloudflare-dns.com", p)
        assertTrue(v.blocked)
        assertEquals(Reason.BLOCKED_DOH, v.reason)
        assertTrue(DomainFilter.explain(v, p).contains("encrypted-DNS"))
    }

    @Test fun thePrivateDnsProbeIsAlwaysAnsweredWithNxDomain() {
        // use-application-dns.net must be blocked even for a parent-allow-everything policy,
        // otherwise Android Private DNS quietly bypasses the whole filter.
        val v = filter.evaluate("use-application-dns.net", policy(AgeProfile.KIDS_3_7, allow = setOf("use-application-dns.net")))
        assertTrue(v.blocked)
        assertEquals(Reason.BLOCKED_DOH, v.reason)
    }
}
