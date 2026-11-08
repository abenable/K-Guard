# K-Guard — Feature Documentation

K-Guard is a two-sided child-safety platform: a **parent app** for supervision and control, and a
**child-device agent** for enforcement, backed by Firebase (Auth, Firestore, Cloud Functions, FCM,
Storage). One install becomes either side based on the role chosen at sign-in.

- **Package:** `com.kguard.app` · Android (Jetpack Compose), minSdk 24, targetSdk 35
- **Backend:** Firebase Cloud Functions (`functions/src/index.ts`), Firestore, FCM
- **Build:** APKs are built and run on the Mac testbed (studio-team02, with its emulator): `./gradlew :app:assembleDebug` / `:app:assembleRelease` · Tests: `./gradlew :app:testDebugUnitTest` (99 unit tests)

---

## 1. Accounts & Pairing

| Feature | How it works |
|---|---|
| **Parent sign-in (Google-only)** | One-tap Google via Credential Manager → Firebase Auth. No email/password anywhere in the app. |
| **Child device pairing — no account needed** | The parent generates a 6-digit code in the child editor; the child's phone needs **no Google account**: the app creates an anonymous device identity and the code binds it to the child profile (`redeemPairingCode` Cloud Function). |
| **Role separation** | `users/{uid}` carries `role: parent|child` + `familyId`; every Cloud Function validates role server-side. Parent accounts can never redeem child pairing codes and vice-versa. |
| **First-run onboarding** | Welcome screen (features + how-it-works) before sign-in. |

**Setup flows**
- *Parent:* Onboarding → Google sign-in → Home dashboard (family is auto-created server-side on first sign-in).
- *Child:* Onboarding → "Child device" → enter 6-digit code (the app moves on only once the code is accepted) → set uninstall PIN → turn on uninstall protection → child dashboard → allow the protections in the setup checklist.

## 2. Child Profiles

- Create/edit child profiles: name, photo, age profile. Photos are resized on the phone (upright, square, 512 px JPEG) and uploaded to Storage (`users/{parentUid}/avatars/`, owner-only, < 2 MB), so they show on every phone in the family.
- **Per-child lock switch** on the children list: lock now or later, until unlocked or for a set time.
- **Age profiles (4 tiers):** Kids 3–7 · Pre-Teens 8–12 · Teens 13–17 · Adults 18+ — every protection keys off this.
- **Per-category web rules:** 10 safety categories (cyberbullying, threat, violence, sexual, self-harm, hate, drugs, profanity, scam, personal info) with parent rules Block / Warn / Blur / Allow / Default. Sexual content is **locked against relaxing under 18** server-side and client-side.
- **Reveal control:** parents decide whether blurred content can be revealed on the child's device (disabled for younger profiles).
- **Pairing:** generates the 6-digit code that links a child device (status shown as Paired / Not linked).

## 3. AI Content Moderation (text & images)

- **Text moderation:** the child submits text; the backend (Cloud Function `moderateContent`) analyses it and returns a category, a risk score, and a decision.
- **Image moderation:** images (≤ 8 MB) are uploaded to Storage and analysed by the same pipeline.
- **Engine:** Google Cloud Natural Language `moderateText` + Cloud Vision SafeSearch, with a keyword rule-engine fallback so moderation still works if the paid APIs are unavailable. **Fail-closed:** an unreachable backend returns REVIEW, never ALLOW.
- **Decisions:** ALLOW · WARN · BLUR · BLOCK · REVIEW — mapped through **age-aware thresholds** (e.g. block ≥ 0.50 for kids vs ≥ 0.92 for adults) and the parent's per-category rules.
- **Context softening:** educational/news/fiction context can soften WARN/BLUR (never for kids 3–7); sexual/threat/self-harm are **never softened**.
- **Reveal flow:** blurred content can be revealed in-app when the parent allows it.

## 4. Web Protection (on-device VPN filter, set remotely by the parent)

A foreground `VpnService` on the **child's phone** routes its DNS through K-Guard; every lookup is filtered locally.

- **Remote settings per child:** the parent edits on/off, custom block/allow lists, educational sites and the AI filter for each child; they're stored in `families/{id}/webProtection/{childId}` and applied on the child's phone within seconds (`ChildWebProtectionSync`). The parent's phone runs no filter of its own.
- **Status from the child's phone:** running, needs a one-time consent tap, turned off on the device, last report. The parent gets a push when protection is switched off on the phone or needs consent (`onWebProtectionStatus`).
- **Web-block alerts** for the selected child with Keep blocked / Allow site / Dismiss.
- **Turning it off on the phone doesn't work:** if the child disconnects the VPN, K-Guard restarts it within seconds; if the filter stays off for 20 s while the parent has web protection on (for example the child chose "Forget VPN" or connected another VPN app), the phone shows the lock screen with **Turn web protection back on** (opens Android's one-time VPN prompt). Emergency calls and the parent PIN still work. The blocklists and the local AI classifier both run inside this filter, so the lock covers both.
- **Always-on VPN (setup checklist, Android 10+):** the child's phone is walked through turning on Always-on VPN for K-Guard, so Android restarts the filter itself and no other VPN app can replace it; the parent sees it as missing until it's on. "Block connections without VPN" is deliberately left off: the filter only carries DNS, so lockdown would cut the phone off the internet.

- **Category blocklists** per age profile (shipped in assets, one domain per line, wildcard support).
- **Local AI domain classifier** — sites on *no* list are judged **on-device** (offline, milliseconds) by a pattern scorer (`vpn/classifier/`): keyword weights per category, benign-vocabulary damping, punycode detection, per-age risk thresholds mirroring the server's policy. Verdicts are cached (24h TTL, auto-invalidated when the age profile or rules change). A parent toggle turns the AI filter off; a swappable `DomainClassifier` interface is ready for a trained ONNX model later.
- **Parent overrides always win:** custom allow/block lists, per-category rules, and the educational allowlist.
- **Safe-search enforcement:** Google/Bing/YouTube lookups are transparently rewritten to their safe-mode hosts.
- **"Check a site" dry-run:** parents can test any domain against the selected child's settings and see the exact reason for its verdict.
- **Encrypted-DNS hardening (partial):** Android Private DNS is disabled via the `use-application-dns.net` NXDOMAIN trick and major public DoH resolvers are hard-blocked (not overridable). Full DoH coverage is a planned SNI-interception phase (see "Known gaps").

## 5. Screen Time & Remote Control

The child's phone runs an always-on enforcement engine (`KGuardCompanionService`, a foreground service) that reads the parent's rules from `families/{id}/devices/{childId}` and enforces them every second; the phone reports telemetry back to the same document. Rules are state, not one-off events, so they survive reboots and offline periods.

- **Whole-phone lock:** a full-screen overlay above every app (master's dark lock screen) with the parent's note, an emergency-call button that always works, and **parent-PIN unlock** (unlocks the phone for 30 minutes; 5 wrong tries → 30 s lockout). Without the overlay permission the engine falls back to switching the screen off (device admin).
- **Remote lock:** now or later, until unlocked or for a set time; the child is warned 10 and 1 minutes before a scheduled lock. **Unlock** clears it; if bedtime or the daily limit caused the lock, they pause for an hour.
- **Bedtime and school hours:** editable start/end times; bedtime locks the whole phone, school hours (Mon–Fri) pause distracting app categories.
- **Daily limit:** 0 = no limit, age-based defaults written the first time a parent opens screen time for a child; **bonus time** counts for the child's current day only.
- **Per-app rules:** pending/rejected apps and per-app daily limits are blocked with a short explanation.
- **Ring alarm:** 30 s at full alarm volume, with a Stop action.
- **Delivery:** every action is logged in `commands` as *pending*; the child's phone marks it *executed* (or *expired* if it was offline) — the parent sees "Sent · waiting for the phone", "Delivered" or "Not delivered". A high-priority push (`onCommandCreated`) wakes the engine.
- **Telemetry (honest):** online/last seen, battery, network, current app, screen time used, pickups, hourly usage, per-app usage, daily history (`devices/{childId}/days/{date}`). Nothing is shown until the phone has reported.
- **Phone health:** the parent sees which protections the child's phone is missing (usage access, display over other apps, device admin, notifications, unrestricted battery) and gets a push when one is switched off (`onDeviceProtectionChanged`) or when a phone goes silent for 6 hours (`checkSilentDevices`).
- **Home dashboard card:** the selected child's screen time today, lock/bedtime status and last sync, with a child switcher and a quick-actions button.

## 6. App Management (install approvals)

- **Child requests an app** (presets or custom name/package): lands in the parent's queue.
- **Parent approves / declines**, optionally with a per-app daily limit; the child gets an approval push.
- **Safety score & reason** per app (AI-assisted), status tags (Approved / Pending / Rejected), revoke support.
- **App inventory on the child's phone:** apps already installed at setup are recorded as approved (`syncInstalledApps`); new installs become requests; uninstalls are reported (`reportAppRemoved`) and shown as an install/uninstall history on Screen Time. Works offline (queued) and reconciles on start.
- Search, per-status counts, a block switch per app, "Uninstalled" tags, confirm dialogs, and 0 = no per-app limit.

## 7. Alerts & Review Queue

- Every moderation/web block can create a `SafetyEvent` that lands in the parent's **review queue**.
- **Parent actions:** Allow once · Keep blocked · Dismiss — each with an optional note; audit trail on the event.
- Search across child, category, explanation and snippet; filter counts; snippets blurred until the parent reveals them.
- **Open-alert badge** on the dashboard (count from a dedicated listener, includes older open events).
- **Privacy controls:** notify-on level (all / warn+ / block-only), quiet hours with cross-midnight windows, snippet inclusion toggle (flagged content hidden from notifications unless allowed), push-token registration per device.

## 8. System-Level Protection (child device)

- **Uninstall protection (device administrator):** activation is a dedicated setup step; while active, **Android itself blocks uninstalling K-Guard**, and remote lock is enabled. The disable-request dialog carries an explicit deterrent message; deactivation triggers a tamper alert and re-enable nagging.
- **Tamper watchdog:** AlarmManager cycle (every 15 min, re-armed at boot and on app open) that restarts the VPN service if it was killed while protection is on, and rate-limits a "protection is weakened" notification until device admin is re-enabled.
- **Boot persistence:** web protection restarts after reboot when the parent left it on.
- **Uninstall guard:** opening Android's "deactivate device admin" screen without the parent PIN locks the phone and brings up K-Guard's PIN prompt; with the PIN, K-Guard releases its admin and opens the uninstall screen (the parent is told).
- **Engine persistence:** the enforcement engine restarts on boot and app update, from the watchdog, and on a remote-command push. On Android 12+ a background alarm may only restart it when K-Guard has unrestricted battery; otherwise the watchdog posts "K-Guard protection is paused — tap to turn it back on" (at most hourly).
- **Parent PIN:** PBKDF2-hashed; raw PIN never stored. Unlocks the lock screen and allows removal.
- **Honest limit:** a non-device-owner app can still be deactivated via system settings; the watchdog + nag + tamper alerts are the counterweight (device-owner/MDM provisioning is the future hard path).

## 9. Child Experience

- Calm, friendly child dashboard ("You're protected…") with real screen time left, today's schedules and live status; a "Pair this phone" entry when it isn't linked.
- **Protection setup checklist:** usage access, display over other apps, device admin, notifications, unrestricted battery — each with an Allow button; hidden once everything is granted.
- Fullscreen lock overlay designed for night use (emergency call always reachable).
- PIN setup with strength feedback; uninstall-protection activation step.

## 10. K-Guard Live (AI screen check, child's consent)

- Started by the child from the dashboard; Android's screen-sharing prompt is the consent. A foreground service captures the screen every 5 s (540 px wide, near-duplicate frames skipped).
- **On-device check (always, offline):** every new screen is checked on the phone by Marqo's `nsfw-image-detection-384` (Apache-2.0, 22 MB, shipped in the APK under `assets/models/nsfw-384/`), the model and preprocessing Trawl (Crane AI Labs) uses, on ONNX Runtime. Thresholds per age: Kids/Pre-teens block at ≥ 0.90; Teens warn at ≥ 0.90 and block at ≥ 0.94; Adults warn at ≥ 0.94 (Trawl's measured points). No frame leaves the phone for this check.
- **Cloud check (optional):** only when `kguard.ai.url` (self-hosted, OpenAI-compatible) or `kguard.gemini.keys` is set in `local.properties`, screens that pass the on-device check are also checked for the other categories (violence, self-harm, hate, drugs, …). Those frames do leave the phone; keys set there are compiled into that APK.
- CAUTION covers the screen with a blurred copy and a message (reveal allowed only for teens/adults when the parent allows it); HARMFUL also sends the phone to the home screen. A soft edge glow shows Live is on.
- Alerts land in the parent's review queue (at most one per category per app every 3 minutes).

## Known gaps (tracked, not shipped)

| Gap | Status |
|---|---|
| AI Parent Assistant | Ported code exists on `origin/local-updates`; deferred until model calls go through a Cloud Function (no keys in the APK) |
| K-Guard Live, other categories on-device | Sexual content is checked on the phone; violence/weapons/gore still need the optional cloud model. Next: TinyCLIP zero-shot (also Trawl's) as a downloadable model pack |
| K-Guard Live thresholds on screenshots | Thresholds were measured by Trawl on photos; re-measure on real screen captures || Managed-device mode | Device-owner enrolment (factory reset + QR) would let K-Guard force Always-on VPN and block VPN settings entirely |
| Full DoH bypass coverage | Public resolvers (Cloudflare, Google, Quad9, NextDNS incl. Brave's default, AdGuard, ControlD — IPv4+IPv6) are routed into the tunnel and dropped, so "secure DNS" falls back to the filtered system resolver. Still open: private DoH on arbitrary IPs (needs TCP/443 routing + TLS SNI sniffing, phase 2) |
| Telemetry write volume | The child's phone writes its device document at least once a minute and on app switches; trim before scaling |
| DST edge in quiet hours | Frozen `utcOffsetMinutes` gives wrong windows across DST transitions |
| Pairing-code brute-force limiting | 6-digit codes are rate-limitable server-side; attempt counter recommended before rollout |
| Force-stop | A force-stop kills the engine and its alarms until the next boot or app open; the parent is told after 6 h of silence |
| Google sign-in certificates | Requires the release/debug fingerprints to be registered in the Firebase project (pending) |

## Repository map

```
app/src/main/java/com/tele/k_guard/
  ui/            screens (17), navigation, components (KG kit), theme (light-only brand)
  viewmodel/     SessionViewModel (auth/family), ScreenTime, AppManagement, moderation VMs
  data/          model/, repository/ (Family, ParentalControls, Moderation, UserPreferences)
  vpn/           KGuardVpnService, DomainFilter, classifier/ (on-device AI), config/stats
  protection/    DeviceProtection core + tamper watchdog
  receiver/      device admin
  screentime/    rules + lock evaluator, usage reader, app inventory, lock overlay, uninstall guard
  live/          K-Guard Live (screen capture, AI check, shield overlay)
  service/       KGuardCompanionService (child enforcement engine), FCM messaging
functions/src/   Cloud Functions: family/pairing, moderation, approvals, app inventory
                 (syncInstalledApps, reportAppRemoved), web-block reports, command delivery
                 (onCommandCreated), protection/web-status/silent-device alerts, scheduled purge
```
