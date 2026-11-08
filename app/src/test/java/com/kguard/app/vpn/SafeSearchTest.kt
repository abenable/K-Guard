package com.kguard.app.vpn

import com.kguard.app.data.model.AgeProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSearchTest {

    private val teen = AgeProfile.TEENS_13_17

    @Test fun googleAndItsCountryDomainsGoToForceSafeSearch() {
        for (host in listOf("www.google.com", "google.com", "www.google.co.ug", "google.co.uk", "www.google.com.au", "www.google.fr")) {
            assertEquals(host, SafeSearch.GOOGLE, SafeSearch.targetFor(host, teen))
        }
        assertEquals(SafeSearch.GOOGLE, SafeSearch.targetFor("WWW.Google.COM.", teen)) // case and trailing dot
    }

    @Test fun otherGoogleAndLookalikeHostsAreLeftAlone() {
        for (host in listOf("maps.google.com", "forcesafesearch.google.com", "notgoogle.com", "google.com.evil.com", "www.google.com.evil.io", "example.com")) {
            assertNull(host, SafeSearch.targetFor(host, teen))
        }
    }

    @Test fun youtubeAndBingGoToTheirSafeHosts() {
        for (host in listOf(
            "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com",
            "www.youtube-nocookie.com", "youtube.com", "music.youtube.com", "youtube-nocookie.com"
        )) {
            assertEquals(host, SafeSearch.YOUTUBE, SafeSearch.targetFor(host, AgeProfile.PRETEENS_8_12))
        }
        for (host in listOf("www.bing.com", "bing.com")) {
            assertEquals(host, SafeSearch.BING, SafeSearch.targetFor(host, teen))
        }
        assertNull(SafeSearch.targetFor("restrict.youtube.com", teen)) // the target itself is never rewritten
    }

    @Test fun duckDuckGoIsNotRewrittenBecauseItHasNoDnsSafeHost() {
        assertNull(SafeSearch.targetFor("duckduckgo.com", teen))
        assertNull(SafeSearch.targetFor("www.duckduckgo.com", teen))
    }

    @Test fun adultsAreNotRewritten() {
        for (host in listOf("www.google.com", "www.youtube.com", "youtube.com", "music.youtube.com", "bing.com", "www.bing.com")) {
            assertNull(host, SafeSearch.targetFor(host, AgeProfile.ADULTS_18_PLUS))
        }
    }

    @Test fun builtQueriesParseBack() {
        val q = DnsPacket.buildQuery("strict.bing.com", 28)
        assertEquals("strict.bing.com", DnsPacket.parseName(q))
        assertEquals(28, DnsPacket.parseQType(q))
    }

    @Test fun cnameAnswerPointsTheNameAtTheTargetAndCarriesItsAddress() {
        val query = DnsPacket.buildQuery("www.google.com", 1, id = 0x1234)
        val ip = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val a = DnsPacket.cnameAnswer(query, SafeSearch.GOOGLE, listOf(ip))

        assertEquals(0x12, a[0].toInt() and 0xff); assertEquals(0x34, a[1].toInt() and 0xff) // id echoed
        assertTrue("QR bit set", (a[2].toInt() and 0x80) != 0)
        assertEquals("RCODE 0", 0, a[3].toInt() and 0x0f)
        assertEquals("QDCOUNT", 1, u16(a, 4))
        assertEquals("ANCOUNT = CNAME + one address", 2, u16(a, 6))

        val q = 12 + 16 + 4                                  // header + "www.google.com" + QTYPE/QCLASS
        assertEquals(0xC0, a[q].toInt() and 0xff); assertEquals(0x0C, a[q + 1].toInt() and 0xff) // owner: the question name
        assertEquals("type CNAME", 5, u16(a, q + 2))
        val rdStart = q + 12
        val rdLen = u16(a, q + 10)
        assertEquals(SafeSearch.GOOGLE, readName(a, rdStart))

        val second = rdStart + rdLen
        assertEquals("owner points at the CNAME target", 0xC000 or rdStart, u16(a, second))
        assertEquals("type A", 1, u16(a, second + 2))
        assertEquals(4, u16(a, second + 10))
        assertArrayEquals(ip, a.copyOfRange(second + 12, second + 16))

        assertArrayEquals(ip, DnsPacket.parseAddresses(a, 1).single()) // round trip through the parser
    }

    @Test fun ipv6AddressesAreAnsweredAsAaaa() {
        val query = DnsPacket.buildQuery("www.youtube.com", 28)
        val ip6 = ByteArray(16) { (it + 1).toByte() }
        val a = DnsPacket.cnameAnswer(query, SafeSearch.YOUTUBE, listOf(ip6))
        assertArrayEquals(ip6, DnsPacket.parseAddresses(a, 28).single())
        assertTrue(DnsPacket.parseAddresses(a, 1).isEmpty())
    }

    @Test fun cnameOnlyAnswerWhenTheTargetHasNoAddress() {
        val a = DnsPacket.cnameAnswer(DnsPacket.buildQuery("www.google.com", 28), SafeSearch.GOOGLE, emptyList())
        assertEquals(1, u16(a, 6))
        assertTrue(DnsPacket.parseAddresses(a, 28).isEmpty())
    }

    @Test fun noDataHasNoAnswersAndNoError() {
        val query = DnsPacket.buildQuery("www.google.com", 65, id = 0xABCD)
        val a = DnsPacket.noData(query)
        assertEquals(0xABCD, u16(a, 0))
        assertEquals(0, a[3].toInt() and 0x0f)
        assertEquals(0, u16(a, 6))
        assertEquals(12 + 16 + 4, a.size)
    }

    @Test fun errorsAndBrokenPacketsGiveNoAddresses() {
        val query = DnsPacket.buildQuery("example.com", 1)
        assertTrue(DnsPacket.parseAddresses(DnsPacket.nxDomain(query), 1).isEmpty()) // NXDOMAIN
        assertTrue(DnsPacket.parseAddresses(query, 1).isEmpty())                    // a query, not a response
        assertTrue(DnsPacket.parseAddresses(ByteArray(5), 1).isEmpty())
        val full = DnsPacket.cnameAnswer(query, "x.example.com", listOf(byteArrayOf(1, 2, 3, 4)))
        assertTrue(DnsPacket.parseAddresses(full.copyOf(full.size - 6), 1).isEmpty())  // cut in the middle of a record
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    /** Reads an uncompressed name starting at [pos]. */
    private fun readName(b: ByteArray, start: Int): String {
        val parts = ArrayList<String>()
        var pos = start
        while (b[pos].toInt() != 0) {
            val len = b[pos].toInt() and 0xff
            parts += String(b, pos + 1, len, Charsets.US_ASCII)
            pos += len + 1
        }
        return parts.joinToString(".")
    }
}
