# Ilona (working tree)

Voice-first AI companion for seniors — personal/family project, given
away free (never sold).

- **Brief:** `../files/nuri-project-brief.md` (consolidated, updated 2026-10-16)
- **Scaffold notes:** `SCAFFOLD.md`
- **License:** GPL-3.0-or-later for distributed builds (per `../briefs/license-audit.md`
  — espeak-ng copyleft via the voice engine); ViFork's MIT applies to the upstream
  skeleton. Repo roots carry `LICENSE` + `THIRD-PARTY-NOTICES`. A linking
  exception covers the Google ML Kit and Play services libraries inside
  the app — see [`LINKING-EXCEPTION`](LINKING-EXCEPTION). Terms of use:
  [`TERMS.md`](TERMS.md). Privacy policy:
  https://smartyourtv.github.io/nuri/privacy-policy

> Ilona is a free helper app — not a medical device. It never calls
> 911 by itself; in an emergency, call 911. Medicine reminders are
> memory aids based on a schedule you enter, not medical advice.
> Safety features are aids that can miss things — please don't rely
> on Ilona alone for anyone's safety.

Status: **features built 2026-10-16** (Phase D + bug hunt): tiers, first-install
modes, caregiver dashboard, backup/restore, Bluetooth memory, game, readability
standard + adjustable appearance, self-update policy/verification layer.
**2026-10-17:** the self-update EXECUTOR is built (nightly scheduled check with
night/Wi-Fi/charger gating, streaming verified download, PackageInstaller
install with the Android 12+ no-user-action request and one-tap fallback,
post-update self-test with caregiver alert + quiet after-note; the per-variant
feed address is a BuildConfig field, blank until distribution is decided), and
NU-5 is fixed (therapist consent-seen state is read and surfaced in the
first-install flow and Settings). 43 unit tests passing in both flavors.

Repos (uploaded 2026-10-17):
- Private (full source, owner only): `smartyourtv/nuri-private`
- Public (public flavor only): `smartyourtv/nuri`
