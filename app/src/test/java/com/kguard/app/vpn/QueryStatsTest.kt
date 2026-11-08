package com.kguard.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryStatsTest {

    /** A DNS query for [name] with the given record type (header, one question, no extras). */
    private fun query(name: String, type: Int): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0).map { it.toByte() } // id, RD, QDCOUNT=1
        for (label in name.split('.')) {
            out += label.length.toByte()
            out += label.toByteArray().toList()
        }
        out += 0.toByte()
        out += listOf((type shr 8).toByte(), type.toByte(), 0.toByte(), 1.toByte()) // QTYPE, QCLASS=IN
        return out.toByteArray()
    }

    @Test fun readsTheRecordTypeOfTheQuestion() {
        assertEquals(1, DnsPacket.parseQType(query("example.com", 1)))
        assertEquals(28, DnsPacket.parseQType(query("www.example.co.ug", 28)))
        assertEquals(65, DnsPacket.parseQType(query("a.b", 65)))
    }

    @Test fun typeNamesAreReadable() {
        assertEquals("A", DnsPacket.typeName(1))
        assertEquals("AAAA", DnsPacket.typeName(28))
        assertEquals("HTTPS", DnsPacket.typeName(65))
        assertEquals("TYPE999", DnsPacket.typeName(999))
    }

    @Test fun malformedQuestionsGiveNoType() {
        val ok = query("example.com", 1)
        assertNull(DnsPacket.parseQType(ok.copyOf(ok.size - 3)))   // cut inside QTYPE/QCLASS
        assertNull(DnsPacket.parseQType(ByteArray(5)))             // shorter than a header
        val pointer = ok.copyOf(); pointer[12] = 0xC0.toByte()     // compression pointer in a question
        assertNull(DnsPacket.parseQType(pointer))
    }

    @Test fun countsBlockedAndFlaggedLookups() {
        var day = DayCounts()
        day = day.withQuery(wasBlocked = false, category = null)       // ordinary lookup
        day = day.withQuery(wasBlocked = true, category = "sexual")    // blocked, on a list
        day = day.withQuery(wasBlocked = false, category = "drugs")    // allowed by a parent rule, still flagged
        day = day.withQuery(wasBlocked = true, category = "sexual")
        assertEquals(4, day.queries)
        assertEquals(2, day.blocked)
        assertEquals(mapOf("sexual" to 2, "drugs" to 1), day.flagged)
    }

    @Test fun countsSurviveEncodingAndBadDataIsIgnored() {
        val day = DayCounts(12, 3, mapOf("sexual" to 2, "scam" to 1))
        assertEquals(day, DayCounts.decode(day.encode()))
        assertEquals(DayCounts(5, 0, emptyMap()), DayCounts.decode(DayCounts(5, 0).encode()))
        assertEquals(DayCounts(), DayCounts.decode(null))
        assertEquals(DayCounts(), DayCounts.decode("not,a,number=x"))
    }

    @Test fun percentilesUseNearestRank() {
        val v = (1L..20L).toList()
        assertEquals(10, percentile(v, 50))
        assertEquals(19, percentile(v, 95))
        assertEquals(20, percentile(v, 100))
        assertEquals(7, percentile(listOf(7L), 95))
        assertEquals(0, percentile(emptyList(), 50))
    }
}
