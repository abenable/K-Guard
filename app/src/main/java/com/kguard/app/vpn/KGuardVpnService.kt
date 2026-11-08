package com.kguard.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kguard.app.MainActivity
import com.kguard.app.R
import com.kguard.app.service.KGuardMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * On-device DNS filter. Only the virtual DNS address (10.0.0.1) is routed into the tunnel, so
 * regular traffic never passes through K-Guard. Each lookup is checked; blocked names get NXDOMAIN,
 * everything else is forwarded unchanged to a public resolver.
 */
class KGuardVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val forwarders = Executors.newFixedThreadPool(8)
    private val reporter = WebBlockReporter()
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        QueryMonitor.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VpnConfigStore(this).setEnabled(false)
            // A stop request can arrive via startForegroundService, which still expects startForeground
            // to be called; the notification is removed again immediately by stopFiltering().
            runCatching { showNotification() }
            stopFiltering()
            return START_NOT_STICKY
        }
        // A null intent means Android restarted us; only continue if the parent left protection on.
        if (intent == null && !VpnConfigStore(this).load().enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // establish() must come first: on Android 14+ startForeground(SYSTEM_EXEMPTED) can throw before
        // the tunnel exists, which used to crash every start.
        if (running) showNotification() else startFiltering()
        return START_STICKY
    }

    override fun onRevoke() {
        // User turned the VPN off in system settings.
        VpnConfigStore(this).setEnabled(false)
        stopFiltering()
        // Make sure someone notices: protection is off and websites are no longer filtered.
        // showAlert already checks POST_NOTIFICATIONS on API 33+.
        runCatching {
            KGuardMessagingService.showAlert(
                this,
                "K-Guard protection was turned off",
                "Web protection was switched off in system settings, so websites are no longer filtered. Turn it back on in K-Guard."
            )
        }
    }

    override fun onDestroy() {
        stopFiltering()
        scope.cancel()
        forwarders.shutdownNow()
        super.onDestroy()
    }

    private fun startFiltering() {
        val store = VpnConfigStore(this)
        val filter = DomainFilter.load(this)
        val builder = Builder()
            .setSession("K-Guard Web Protection")
            .addAddress(TUN_ADDRESS, 32)
            .addAddress(TUN_ADDRESS_V6, 128)
            .addDnsServer(DNS_ADDRESS)
            .addDnsServer(DNS_ADDRESS_V6)
            .addRoute(DNS_ADDRESS, 32)
            .addRoute(DNS_ADDRESS_V6, 128)
        // Known public resolvers (DoH and plain) are routed into the tunnel too, where everything not
        // addressed to K-Guard's own fake resolvers is dropped. A browser's built-in "secure DNS"
        // (Brave enables it out of the box) then fails and falls back to the system resolver, which
        // K-Guard filters. The upstream lookup itself uses protect(), so it never enters the tunnel.
        for ((ip, prefix) in BLACKHOLED_ROUTES) runCatching { builder.addRoute(ip, prefix) }
            .onFailure { Log.w("KGuardVpn", "Could not route $ip for blackholing", it) }
        builder.setBlocking(true)
        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            showNotification() // a startForegroundService must report in even when we cannot run
            stopFiltering()
            return
        }
        tun = pfd
        running = true
        instance = this
        VpnState.setRunning(true)
        store.setEnabled(true)
        showNotification()
        val ownDns4 = InetAddress.getByName(DNS_ADDRESS).address
        val ownDns6 = InetAddress.getByName(DNS_ADDRESS_V6).address

        scope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (running) {
                val n = try { input.read(buf) } catch (e: Exception) { break }
                if (n <= 0) continue
                // Only K-Guard's own fake resolvers are answered. Anything else routed in here — a
                // browser's built-in DoH, or a plain lookup to a foreign resolver — is dropped, so it
                // times out and the app falls back to the system resolver, which K-Guard filters.
                val dst = DnsPacket.destination(buf, n) ?: continue
                if (!dst.contentEquals(ownDns4) && !dst.contentEquals(ownDns6)) continue
                val q = DnsPacket.parseQuery(buf, n) ?: continue
                handle(q, filter, store, output)
            }
        }
    }

    private fun handle(q: DnsPacket.Query, filter: DomainFilter, store: VpnConfigStore, out: FileOutputStream) {
        val started = System.nanoTime()
        val name = q.name
        val type = DnsPacket.typeName(q.qtype ?: 0)
        // Re-read each time so a settings change applies immediately.
        val cfg = store.load()
        val verdict = if (name == null) Verdict(false) else filter.evaluate(name, cfg.policy)

        if (verdict.blocked && name != null) {
            write(out, DnsPacket.reply(q, DnsPacket.nxDomain(q.dns)))
            val explanation = DomainFilter.explain(verdict, cfg.policy)
            VpnState.recordBlock(name, verdict.category?.label ?: "K-Guard protection", explanation)
            notifyBlocked(explanation)
            val childId = cfg.childId
            if (childId != null) scope.launch { reporter.report(childId, verdict.category, name) }
            QueryMonitor.record(name, type, verdict, QueryMonitor.Outcome.BLOCKED, elapsedMs(started))
            return
        }
        // Search engines and YouTube are pointed at their safe-mode hosts instead of being answered normally.
        val safeTarget = if (name != null) SafeSearch.targetFor(name, cfg.policy.ageProfile) else null
        forwarders.execute {
            val upstreamStarted = System.nanoTime()
            // If the safe host can't be resolved the lookup goes unanswered; it never falls back to the unsafe original.
            val answer = if (safeTarget != null) safeAnswer(q.dns, safeTarget) else forward(q.dns)
            val upstreamMs = elapsedMs(upstreamStarted)
            if (answer == null) { // unanswered: the app retries, like a normal timeout
                if (name != null) QueryMonitor.record(name, type, verdict, QueryMonitor.Outcome.UNANSWERED, 0)
                return@execute
            }
            write(out, DnsPacket.reply(q, answer))
            // "Added" latency is what K-Guard itself cost, so the upstream resolver's time is taken out.
            if (name != null) {
                val outcome = if (safeTarget != null) QueryMonitor.Outcome.REWRITTEN else QueryMonitor.Outcome.ALLOWED
                QueryMonitor.record(name, type, verdict, outcome, (elapsedMs(started) - upstreamMs).coerceAtLeast(0))
            }
        }
    }

    /** Answers [dns] as an alias of [target]. A/AAAA get the target's real addresses behind a CNAME. */
    private fun safeAnswer(dns: ByteArray, target: String): ByteArray? {
        val type = DnsPacket.parseQType(dns) ?: return null
        return when (type) {
            1, 28 -> {
                val upstream = forward(DnsPacket.buildQuery(target, type)) ?: return null
                DnsPacket.cnameAnswer(dns, target, DnsPacket.parseAddresses(upstream, type))
            }
            // HTTPS/SVCB records can carry real addresses and would let a browser skip the rewrite.
            64, 65 -> DnsPacket.noData(dns)
            else -> forward(dns) // MX, TXT and so on: nothing to enforce
        }
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private var lastNotice = 0L

    /** Immediate on-device warning. Shows the reason, never the site name, and is rate-limited. */
    private fun notifyBlocked(explanation: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotice < 30_000) return
        lastNotice = now
        com.kguard.app.service.KGuardMessagingService.showAlert(this, "K-Guard blocked a website", explanation)
    }

    /** Asks the upstream resolver over UDP; falls back to TCP when the reply was truncated. */
    private fun forward(dns: ByteArray): ByteArray? = runCatching {
        val udp = udpForward(dns) ?: return@runCatching null
        if ((udp[2].toInt() and 0x02) == 0) return@runCatching udp // TC bit clear: the answer is complete
        // Truncated: the real answer did not fit in UDP, so re-resolve over TCP.
        tcpForward(dns) ?: DnsPacket.servFail(dns)
    }.getOrNull()

    private fun udpForward(dns: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            protect(s) // keep the forwarded query out of our own tunnel
            s.soTimeout = 4000
            s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(UPSTREAM_DNS), 53))
            val resp = ByteArray(4096)
            val p = DatagramPacket(resp, resp.size)
            s.receive(p)
            val answer = resp.copyOf(p.length)
            // Only accept a response that carries the id of the query we sent.
            if (answer.size < 2 || answer[0] != dns[0] || answer[1] != dns[1]) null else answer
        }
    }.getOrNull()

    /** DNS over TCP: a two-byte length prefix, then the message, for both directions. */
    private fun tcpForward(dns: ByteArray): ByteArray? = runCatching {
        java.net.Socket().use { s ->
            protect(s)
            s.soTimeout = 4000
            s.connect(java.net.InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), 53))
            val out = s.getOutputStream()
            out.write(byteArrayOf((dns.size shr 8).toByte(), dns.size.toByte()))
            out.write(dns)
            out.flush()
            val input = s.getInputStream()
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) return@runCatching null
            val len = (hi shl 8) or lo
            if (len <= 0) return@runCatching null
            val resp = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(resp, read, len - read)
                if (n < 0) return@runCatching null
                read += n
            }
            if (resp[0] != dns[0] || resp[1] != dns[1]) null else resp // id must match what we asked
        }
    }.getOrNull()

    @Synchronized
    private fun write(out: FileOutputStream, packet: ByteArray) {
        runCatching { out.write(packet) }
    }

    private fun stopFiltering() {
        running = false
        if (instance === this) instance = null
        QueryMonitor.flush()
        VpnState.setRunning(false)
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Web protection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("K-Guard web protection is on")
            .setContentText("Harmful websites are blocked for this profile.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // The typed call can still be rejected (wrong type at this moment, OEM quirks); the plain
        // variant is always permitted for a service that was started with startForegroundService.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { runCatching { startForeground(NOTIFICATION_ID, n) } }
    }

    companion object {
        const val ACTION_STOP = "com.kguard.app.vpn.STOP"

        @Volatile private var instance: KGuardVpnService? = null

        /**
         * Whether Android's "Always-on VPN" is set for K-Guard; null while the filter isn't running or below
         * Android 10 (the system only tells the running VPN). With it on, Android restarts the filter itself and
         * no other VPN app can take over. ("Block connections without VPN" stays off: this VPN only carries DNS,
         * so lockdown would cut the phone off the internet.)
         */
        fun isAlwaysOn(): Boolean? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) instance?.isAlwaysOn else null
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val DNS_ADDRESS = "10.0.0.1"
        // A second, IPv6 fake resolver. Only this /128 is routed, so no other IPv6 traffic enters the tunnel.
        private const val TUN_ADDRESS_V6 = "fd00:4b47:4b47:0::2"
        private const val DNS_ADDRESS_V6 = "fd00:4b47:4b47:0::1"
        private const val UPSTREAM_DNS = "1.1.1.1"

        /**
         * Public resolvers (DoH and plain) routed into the tunnel and dropped, so a browser's built-in
         * "secure DNS" — Brave turns it on out of the box — cannot bypass the filter and falls back to
         * the system resolver K-Guard controls. These ranges host no websites, so nothing legitimate dies.
         */
        private val BLACKHOLED_ROUTES = listOf(
            "1.1.1.0" to 24, "1.0.0.0" to 24,                    // Cloudflare (1.1.1.1, family variants)
            "8.8.8.8" to 32, "8.8.4.4" to 32,                    // Google
            "9.9.9.9" to 32, "149.112.112.112" to 32,            // Quad9
            "208.67.222.222" to 32, "208.67.220.220" to 32,      // OpenDNS
            "94.140.14.14" to 32, "94.140.15.15" to 32,          // AdGuard
            "45.90.28.0" to 24, "45.90.30.0" to 24,              // NextDNS (Brave's default DoH)
            "76.76.2.0" to 24,                                   // ControlD
            "2606:4700:4700::" to 48,                            // Cloudflare v6
            "2001:4860:4860::" to 48,                            // Google v6
            "2620:fe::" to 48,                                   // Quad9 v6
            "2a10:50c0::" to 32,                                 // AdGuard v6
            "2a07:a8c0::" to 32, "2a07:a8c1::" to 32             // NextDNS v6
        )
        private const val CHANNEL = "web_protection"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val i = Intent(context, KGuardVpnService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            // startService would throw from a background context on Android 12+; the service calls
            // startForeground on the stop path, so the foreground-service contract is still met.
            ContextCompat.startForegroundService(
                context, Intent(context, KGuardVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
