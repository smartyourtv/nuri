# Nuri Scaffold — what was built (2026-10-16, Build 3)

Frame before walls: a compiling Android project on Scout's bones, with empty
screens, pinned dependencies, and a working diagnostics card. **No real
features.** Everything feature-shaped is marked `TODO(GATED)` or lives behind
a placeholder composable.

## Bones mapping (Scout → Nuri)

| Scout (`ai-assistant-android`) | Nuri scaffold |
|---|---|
| ViFork base, Kotlin, Compose, AGP 8.12.3 / Kotlin 1.9.0 | Same, pinned in `gradle/libs.versions.toml` |
| `ScoutApplication.kt` | `NuriApplication.kt` (empty setup) |
| `MainActivity.kt` (Compose shell) | `MainActivity.kt` (Home / Health check / Settings tabs) |
| `repository/` SettingsRepository (DataStore) | `repository/SettingsRepository.kt` (emotion-level wired as pattern example) |
| `viewmodel/` layering | `viewmodel/HomeViewModel.kt` (placeholder) |
| Wake-word diagnostics card pattern | `diagnostics/` — screen + ViewModel + one-tap self-test, from day one |
| `speech/` (WakeWordDetector, VoiceState…) | `speech/VoicePipeline.kt` — contracts only; Scout code plugs in at gated voice build; needs "hey nuri" model |
| `tts/` (KokoroTtsManager, TtsTextNormalizer…) | `tts/TtsPipeline.kt` — contract only; sherpa-onnx AAR + Kokoro wiring gated |
| Persona/config system | `config/NuriSettings.kt` — tiers, install modes, access modes, emotion levels, decided defaults only |
| Build/signing chain (env-driven) | Same env-driven signing in `app/build.gradle.kts` |
| minSdk 24 | **minSdk 29** (S10-or-newer, brief §10) |

## What exists

- Gradle project (`:app`), flavors `public` / `private` (identical today;
  stealth source-set guard documented in `app/src/private/README.md`).
- Screens: Home (placeholder), Diagnostics (real card + self-test reporting
  honest scaffold state), Settings (placeholder showing decided defaults).
- Gated placeholder composables (not in navigation): Caregiver Dashboard,
  Games, First-install setup.
- `LICENSE` copied verbatim from Scout (ViFork MIT).

## What is GATED (not built, by design)

Waits for Scout (phone + laptop) lessons + Shawn's go-ahead:
tiers implementation · caregiver dashboard · backup/restore (4 layers) ·
puzzle games · Bluetooth (10 slots / ASHA / BLE trackers) · Google
integrations (Drive/Contacts/Calendar) · emotion-level UI · VAD-gated
Always listen ("Hey Nuri") · wake model · TTS/STT engine wiring · offline
safety layer · first-install setup flows · stealth flavors · updater ·
senior UX layer (giant text, big targets) · release/distribution of any kind.

## Build (Scout's verified invocation pattern)

```bash
cd ~/workspace/goals/senior-ai-companion-app/nuri-android
export JAVA_HOME=$HOME/workspace/toolchain/jdk-17.0.20.1+1 \
  GRADLE_USER_HOME=/home/hatch/.gradle \
  GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
$HOME/workspace/toolchain/gradle-8.13/bin/gradle :app:assemblePublicDebug --offline \
  -Dorg.gradle.java.home=/home/hatch/workspace/toolchain/jbr-21
```

`local.properties` contains only `sdk.dir` (copied from Scout's, no keys).

**Verification status (2026-10-16):** compile NOT yet verified. Two
environment findings from the scaffold attempt:
1. `toolchain/jbr-21-ipv4/bin/java` is a wrapper script that execs
   `jbr-21/bin/java`, so a daemon started through it reports
   java.home=`jbr-21` and Gradle rejects it as a criteria mismatch against
   `org.gradle.java.home=jbr-21-ipv4` (in `~/.gradle/gradle.properties`).
   The `-Dorg.gradle.java.home=.../jbr-21` flag above works around it.
2. With that fixed, the build then blocked on the shared Gradle journal
   lock (`~/.gradle/caches/journal-1`, held by a concurrent Scout build,
   PID 4181) — lock contention between parallel builds sharing one
   GRADLE_USER_HOME, not a scaffold defect. Re-run the command above when
   no other Gradle build is running to complete verification.
