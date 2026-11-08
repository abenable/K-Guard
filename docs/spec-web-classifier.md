# Spec: Local AI Domain Classifier for Web Protection

## Objective

K-Guard's Web Protection VPN currently decides allow/block using curated text
blocklists (`app/src/main/assets/blocklists/*.txt`). Unknown domains fall
through to "allow" — the lists are tiny, so almost everything is unknown.
This feature adds an **on-device classifier** that judges every unlisted
domain (e.g. `boredteachers-blog123.com`) against the child's age profile and
the existing `SafetyCategory` vocabulary, blocking sites no list ever saw.

**User stories**

- As a child's device, when I resolve a domain that matches no blocklist rule,
  K-Guard classifies it locally (offline, milliseconds) and blocks it if the
  predicted category is blocked for the child's `AgeProfile`.
- As a parent, I can see *why* a site was blocked ("AI filter: suspected
  adult content") and override it with the existing Allow/Block custom lists.
- As a parent, I can toggle the AI filter off entirely.

**Out of scope (explicit deferrals):** full TCP/443 routing + TLS SNI
sniffing (the real DoH-closing move), content-level MITM inspection, ONNX
model training pipeline. Each is a follow-up.

## Design

### Decision point

Classification slots into `DomainFilter.evaluate()` — unchanged call site in
`KGuardVpnService.handle()`. Evaluation order becomes:

1. Parent custom allow (unchanged, highest priority)
2. Parent custom block (unchanged)
3. Educational allowlist (unchanged)
4. List-based rules, per category authority rank `LOCKED > RULE > AGE` (unchanged)
5. **NEW — classifier verdict** for domains that reached "allowed" only by
   falling through: classify domain string + age profile → map to
   `SafetyCategory`; if that category is currently blocked for the age
   profile → block with a new reason.

### Classifier interface (swappable)

```kotlin
package com.kguard.app.vpn.classifier

interface DomainClassifier {
    /** @return null when the classifier cannot judge this domain. */
    fun classify(domain: String, age: AgeProfile): ClassifierVerdict?
}

data class ClassifierVerdict(
    val category: SafetyCategory,
    val confidence: Double,        // 0.0..1.0
    val riskScore: Double,         // 0.0..1.0, mirrors server-side scoring
    val label: String              // short human explanation
)
```

**v0 implementation: `HeuristicDomainClassifier`** — pure Kotlin, no ML
dependency. Pattern scorer over the full domain string (not just registrable
domain): tokenised keywords with per-category weights, TLD priors, punycode /
lookalike / ad-hoc-suspicion signals, normalised by length. Mirrors the
server-side thresholds in `functions/src/policy.ts` (KIDS_3_7 block ≥ 0.50 …
ADULTS_18_PLUS block ≥ 0.92). Returns `null` below its confidence floor, in
which case the domain stays allowed exactly as today.

A later `OnnxDomainClassifier` implements the same interface with no changes
to the filter or service. **Never** keep training data or logs from
classifications; classification is memory-only.

### Verdict cache

`VerdictCache` — per-domain cache of classifier results, LRU-bounded
(default 4,096 entries) with a TTL (default 24h), persisted in the existing
`"web_protection"` SharedPreferences as a JSON blob via
`kotlinx-serialization`. Cleared when: age profile changes, custom rules
change, or the AI filter toggles. Cache holds only `{domain → category,
confidence, risk, expiry}` — no timestamps beyond expiry, no query history.

### New policy knob

- `VpnPolicy.aiFilterEnabled: Boolean` (default **true**), surfaced in
  `VpnConfigStore` and the Web Protection settings UI.
- New `DomainFilter.Reason` values: `BLOCKED_AI`, `ALLOWED_AI` (verdict ran
  but stayed under threshold) — both must render in `DomainFilter.explain()`
  and the Web Protection "Check a site" dry-run.
- Blocked-site notification text: "AI filter blocked a website" + category
  explanation, never the domain (matches existing privacy rule).

### DoH partial mitigation (this build)

1. NXDOMAIN for `use-application-dns.net` → disables Android system Private
   DNS (DoT) so system DNS re-enters the tunnel.
2. Known public DoH resolver hostnames (`cloudflare-dns.com`,
   `dns.google`, `mozilla.cloudflare-dns.com`, `dns.quad9.net`,
   `doh.opendns.com`, …) added to a new `blocklists/doh.txt` loaded as a
   hard-blocked list — resolves-and-uses via plain DNS is killed; apps using
   hardcoded-IP DoH remain a documented residual gap for the phase-2 SNI work.

## Tech Stack

- Kotlin 2.4.21, Compose BOM 2026.02.01 (existing app, AGP 9.4.1)
- kotlinx-serialization-json 1.7.3 (cache blob — dependency already present)
- JUnit 4 (existing test setup; no new deps — **no ONNX/LiteRT yet**)

## Commands

```bash
./gradlew test                  # unit tests (VpnLogicTest etc.)
./gradlew :app:assembleDebug    # build
./gradlew installDebug          # device install (Mac testbed: studio-team02)
```

## Project Structure

```
app/src/main/java/com/tele/k_guard/vpn/
  classifier/
    DomainClassifier.kt        # interface + ClassifierVerdict
    HeuristicDomainClassifier.kt   # v0 implementation
    VerdictCache.kt
  DomainFilter.kt              # + aiFilterEnabled path, new Reasons, classify step
  VpnConfigStore.kt            # + aiFilterEnabled persistence, cache wiring
  KGuardVpnService.kt          # + use-application-dns.net special-case (mostly in filter)
app/src/main/java/com/tele/k_guard/ui/screens/WebProtectionScreen.kt
                               # AI filter toggle in Categories/settings area
app/src/main/assets/blocklists/doh.txt
app/src/test/java/com/tele/k_guard/vpn/classifier/   # unit tests
```

## Code Style

Follows existing `vpn/` package conventions — pure Kotlin objects, no DI,
manual injection with default arguments, Compose + Material3 for UI:

```kotlin
// Mirrors existing DomainFilter style: pure functions, data-class results
class HeuristicDomainClassifier : DomainClassifier {
    override fun classify(domain: String, age: AgeProfile): ClassifierVerdict? { ... }
}
```

- `DomainFilter` stays constructible from tests without Android context.
- New state goes through `VpnConfigStore` (SharedPreferences), matching
  `setEducationalAllow` / `addCustomAllow` patterns.

## Testing Strategy

- **Unit tests (JUnit 4)** in `app/src/test/java/com/tele/k_guard/vpn/classifier/`:
  - `HeuristicDomainClassifierTest`: adult-content domains score high for
    KIDS_3_7 and lower for ADULTS_18_PLUS; benign domains (wikipedia,
    khanacademy, duolingo) return null or low risk; gibberish/punycode
    handling; threshold boundaries per age profile.
  - `VerdictCacheTest`: hit/miss/expiry/eviction/invalidation on age change.
- **Extend `VpnLogicTest`**: filter-level tests — unknown domain blocked by
  classifier when category effective-blocked; parent allow overrides AI
  block; AI toggle off restores current behaviour; DoH hostnames blocked;
  `use-application-dns.net` NXDOMAIN; new reasons render in `explain()`.
- Coverage target: every new decision branch has at least one test, matching
  the existing 50/50-passing suite's discipline.

## Boundaries

- **Always:** run `./gradlew test` before committing; keep `DomainFilter`
  Android-context-free; never log classified domains in release builds
  (existing debug-only convention).
- **Ask first:** adding any Gradle dependency (including future ONNX
  Runtime); shipping a bundled ML model; changing the Firestore policy
  mirror in `functions/src/policy.ts`.
- **Never:** send classification results off-device; store classified-domain
  history beyond the verdict cache; block `ADULTS_18_PLUS` profiles via the
  classifier by default (parents opt in via category rules).

## Success Criteria

1. Unlisted adult-content domains (10-item test set: pornhub, xvideos, redtube,
   onlyfans, chaturbate, xhamster, Brazzers-style lookalikes) resolve to
   NXDOMAIN for KIDS_3_7 / PRETEENS_8_12 / TEENS_13_17 profiles without any
   blocklist entry.
2. Benign unknown domains (khanacademy.org, duolingo.com, bbc.co.uk) are
   allowed at all profiles.
3. Parent custom-allow overrides an AI block; custom-block works as before.
4. Toggling AI filter off restores byte-for-byte the pre-feature behaviour.
5. `./gradlew test` passes with the new suites included.
6. Web Protection "Check a site" shows the classifier explanation for
   previously-unknown domains.
7. `use-application-dns.net` and `cloudflare-dns.com` / `dns.google` are
   blocked (NXDOMAIN) while the VPN is active.

## Open Questions

1. Classifier strictness vs false positives on hobbyist/education domains —
   default confidence floor proposed at 0.80; tune after device testing?
2. Should parents see per-category AI sensitivity sliders (phase 2), or is a
   single on/off toggle enough for v1? (Spec assumes toggle only.)
