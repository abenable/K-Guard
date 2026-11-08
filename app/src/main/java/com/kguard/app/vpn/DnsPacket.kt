package com.kguard.app.vpn

/** Minimal IPv4/IPv6 UDP/DNS helpers. Pure Kotlin so they can be unit tested on the JVM. */
object DnsPacket {

    /** A DNS query found inside a raw IP packet read from the TUN interface. */
    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dns: ByteArray
    ) {
        val name: String? get() = parseName(dns)
        val qtype: Int? get() = parseQType(dns)
    }

    /** Returns the DNS query in an IPv4/IPv6 UDP packet addressed to port 53, or null for anything else. */
    fun parseQuery(packet: ByteArray, length: Int): Query? {
        if (length < 28) return null
        return when (packet[0].toInt() shr 4) {
            4 -> parseQuery4(packet, length)
            6 -> parseQuery6(packet, length)
            else -> null
        }
    }

    /** The destination IP of any IPv4/IPv6 packet, or null when the header is not readable. */
    fun destination(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        return when (packet[0].toInt() shr 4) {
            4 -> packet.copyOfRange(16, 20)
            6 -> if (length < 40) null else packet.copyOfRange(24, 40)
            else -> null
        }
    }

    private fun parseQuery4(packet: ByteArray, length: Int): Query? {
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null // UDP only
        val total = u16(packet, 2).coerceAtMost(length)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        if (srcPort == 53) return null // a "query" from port 53 is a spoofed response, not a client lookup
        val payloadStart = ihl + 8
        if (total <= payloadStart + 12) return null
        return Query(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dns = packet.copyOfRange(payloadStart, total)
        )
    }

    /** IPv6 has a fixed 40-byte header followed directly by UDP. Only the fake DNS /128 is routed, so
     *  no extension headers can appear on this path; anything else is dropped. */
    private fun parseQuery6(packet: ByteArray, length: Int): Query? {
        if (length < 48) return null
        if ((packet[6].toInt() and 0xff) != 17) return null // next header must be UDP
        val total = (40 + u16(packet, 4)).coerceAtMost(length)
        val srcPort = u16(packet, 40)
        val dstPort = u16(packet, 42)
        if (dstPort != 53) return null
        if (srcPort == 53) return null
        val payloadStart = 48
        if (total <= payloadStart + 12) return null
        return Query(
            srcIp = packet.copyOfRange(8, 24),
            dstIp = packet.copyOfRange(24, 40),
            srcPort = srcPort,
            dstPort = dstPort,
            dns = packet.copyOfRange(payloadStart, total)
        )
    }

    /** Reads the first question name from a DNS message, lower-cased. Null if malformed or compressed. */
    fun parseName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        // QR bit must be 0 (a query) and there must be at least one question.
        if ((dns[2].toInt() and 0x80) != 0) return null
        if (u16(dns, 4) < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= dns.size) return null
            val len = dns[pos].toInt() and 0xff
            if (len == 0) break
            if (len and 0xC0 != 0 || len > 63) return null // compression pointers are not valid in a question
            if (pos + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) sb.append((dns[pos + i].toInt() and 0xff).toChar())
            pos += len + 1
        }
        return sb.toString().lowercase()
    }

    /** Record type of the first question (1 = A, 28 = AAAA, ...), or null if the message is malformed. */
    fun parseQType(dns: ByteArray): Int? {
        if (dns.size < 13) return null
        var pos = 12
        while (true) {
            if (pos >= dns.size) return null
            val len = dns[pos].toInt() and 0xff
            if (len == 0) break
            if (len and 0xC0 != 0 || len > 63) return null
            pos += len + 1
        }
        if (pos + 5 > dns.size) return null // null label + QTYPE + QCLASS
        return u16(dns, pos + 1)
    }

    fun typeName(type: Int): String = when (type) {
        1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 15 -> "MX"; 16 -> "TXT"
        28 -> "AAAA"; 33 -> "SRV"; 64 -> "SVCB"; 65 -> "HTTPS"
        else -> "TYPE$type"
    }

    /** Header plus question of [query], turned into a response with [rcode] and no records. */
    private fun emptyResponse(query: ByteArray, rcode: Int): ByteArray {
        var pos = 12
        while (pos < query.size && query[pos].toInt() != 0) pos += (query[pos].toInt() and 0xff) + 1
        val end = (pos + 1 + 4).coerceAtMost(query.size) // null label + QTYPE + QCLASS
        val out = query.copyOfRange(0, end)
        val rd = out[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte()          // QR=1, opcode 0, RD copied
        out[3] = (0x80 or rcode).toByte()       // RA=1, RCODE
        out[6] = 0; out[7] = 0                  // ANCOUNT
        out[8] = 0; out[9] = 0                  // NSCOUNT
        out[10] = 0; out[11] = 0                // ARCOUNT
        return out
    }

    /** Builds an NXDOMAIN response for the given query (same id and question, no answers). */
    fun nxDomain(query: ByteArray): ByteArray = emptyResponse(query, 3)

    /** NOERROR with no answers: the name exists but has no records of the asked type. */
    fun noData(query: ByteArray): ByteArray = emptyResponse(query, 0)

    /** SERVFAIL: we could not get an answer for the client (for example a truncated upstream reply). */
    fun servFail(query: ByteArray): ByteArray = emptyResponse(query, 2)

    /**
     * A recursive query for [name] and [type], used to ask the upstream resolver on our own behalf.
     * A fresh random id per query lets us match each response to the query we actually sent.
     */
    fun buildQuery(name: String, type: Int, id: Int = randomId()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)) // RD, QDCOUNT=1
        out.write(encodeName(name))
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1)) // QTYPE, QCLASS=IN
        return out.toByteArray()
    }

    private val idRandom = java.util.Random()
    private fun randomId(): Int = idRandom.nextInt(0x10000)

    /**
     * An answer that says the name asked in [query] is an alias (CNAME) of [target], followed by the
     * target's [addresses] (4 bytes for A, 16 for AAAA). This is how SafeSearch and Restricted Mode are enforced.
     */
    fun cnameAnswer(query: ByteArray, target: String, addresses: List<ByteArray>, ttl: Int = 60): ByteArray {
        val head = emptyResponse(query, 0)
        val count = 1 + addresses.size
        head[6] = (count shr 8).toByte(); head[7] = count.toByte()
        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        val targetName = encodeName(target)

        fun record(owner: ByteArray, type: Int, rdata: ByteArray) {
            out.write(owner)
            out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1)) // TYPE, CLASS=IN
            out.write(byteArrayOf((ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte()))
            out.write(byteArrayOf((rdata.size shr 8).toByte(), rdata.size.toByte()))
            out.write(rdata)
        }

        // The CNAME's owner is the question name (pointer to offset 12); its data is the target name.
        val targetOffset = out.size() + 2 + 2 + 2 + 4 + 2 // owner + type + class + ttl + rdlength
        record(byteArrayOf(0xC0.toByte(), 0x0C), 5, targetName)
        // Address records are owned by the target, which now sits in the message at targetOffset.
        val pointer = byteArrayOf((0xC0 or (targetOffset shr 8)).toByte(), targetOffset.toByte())
        for (a in addresses) record(pointer, if (a.size == 16) 28 else 1, a)
        return out.toByteArray()
    }

    /** The A (type 1) or AAAA (type 28) addresses in a DNS response. CNAME hops and other records are skipped. */
    fun parseAddresses(response: ByteArray, type: Int): List<ByteArray> {
        if (response.size < 12) return emptyList()
        if ((response[2].toInt() and 0x80) == 0) return emptyList() // not a response
        if ((response[3].toInt() and 0x0f) != 0) return emptyList() // an error such as NXDOMAIN
        val want = if (type == 28) 16 else 4
        var pos = 12
        repeat(u16(response, 4)) {
            pos = skipName(response, pos) ?: return emptyList()
            pos += 4 // QTYPE + QCLASS
        }
        val found = ArrayList<ByteArray>()
        repeat(u16(response, 6)) {
            pos = skipName(response, pos) ?: return found
            if (pos + 10 > response.size) return found
            val t = u16(response, pos)
            val rdlen = u16(response, pos + 8)
            pos += 10
            if (pos + rdlen > response.size) return found
            if (t == type && rdlen == want) found += response.copyOfRange(pos, pos + rdlen)
            pos += rdlen
        }
        return found
    }

    /** A name as DNS labels: length-prefixed parts ending in a zero byte. */
    private fun encodeName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            require(label.length in 1..63) { "bad DNS label in $name" }
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Position just after the name that starts at [start], following a compression pointer if there is one. */
    private fun skipName(b: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= b.size) return null
            val len = b[pos].toInt() and 0xff
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return if (pos + 2 <= b.size) pos + 2 else null
            if (len and 0xC0 != 0) return null
            pos += len + 1
        }
    }

    /** Wraps a UDP payload in an IPv4 packet (UDP checksum 0, which IPv4 permits). */
    fun wrapUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        putU16(p, 2, total)
        p[8] = 64                // TTL
        p[9] = 17                // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        putU16(p, 10, ipChecksum(p, 20))
        putU16(p, 20, srcPort)
        putU16(p, 22, dstPort)
        putU16(p, 24, 8 + payload.size)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    /**
     * Wraps a UDP payload in an IPv6 packet. IPv6 does not permit a zero UDP checksum, so the real
     * checksum (over the pseudo-header plus the UDP datagram) is computed here.
     */
    fun wrapUdp6(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val p = ByteArray(40 + 8 + payload.size)
        p[0] = 0x60.toByte()                       // version 6; traffic class and flow label stay zero
        putU16(p, 4, 8 + payload.size)             // payload length
        p[6] = 17                                  // next header: UDP
        p[7] = 64                                  // hop limit
        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)
        putU16(p, 40, srcPort)
        putU16(p, 42, dstPort)
        putU16(p, 44, 8 + payload.size)
        System.arraycopy(payload, 0, p, 48, payload.size)
        putU16(p, 46, udpChecksum6(p))
        return p
    }

    /** UDP checksum for an IPv6 packet [p] built by [wrapUdp6]: pseudo-header + UDP header + payload. */
    private fun udpChecksum6(p: ByteArray): Int {
        val udpLength = p.size - 48
        var sum = 0
        for (i in 8 until 24) sum += u16(p, i)                        // source address
        for (i in 24 until 40) sum += u16(p, i)                       // destination address
        sum += (udpLength shr 16) and 0xffff
        sum += udpLength and 0xffff
        sum += 17                                                     // next header
        sum += u16(p, 40) + u16(p, 42) + u16(p, 44)                   // ports + length (checksum field is zero)
        var i = 48
        while (i + 1 < p.size) { sum += u16(p, i); i += 2 }
        if (i < p.size) sum += (p[i].toInt() and 0xff) shl 8          // odd payload, padded
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        val c = sum.inv() and 0xffff
        return if (c == 0) 0xffff else c                              // zero is transmitted as 0xFFFF in IPv6
    }

    /** Builds the reply packet for [q] carrying [dnsResponse]; source and destination are swapped. */
    fun reply(q: Query, dnsResponse: ByteArray): ByteArray =
        if (q.srcIp.size == 16) wrapUdp6(q.dstIp, q.srcIp, q.dstPort, q.srcPort, dnsResponse)
        else wrapUdp(q.dstIp, q.srcIp, q.dstPort, q.srcPort, dnsResponse)

    fun ipChecksum(buf: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            if (i != 10) sum += u16(buf, i) // skip the checksum field itself
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv() and 0xffff
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
    }
}
