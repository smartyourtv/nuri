# Nuri — voice-first AI companion for seniors

Nuri is a voice-first companion app designed for older adults: big, calm,
easy to read, and honest about what it can and can't do. Talk first; screens
only where they help.

This repository is the **public build** — the distribution source and the
home of the in-app updater feed.

## Features

- Conversation, reminders, check-ins, and an offline safety layer that
  never depends on Wi-Fi
- Three support tiers (Independent / Supported / Guided) with per-person
  toggles; the Guided tier never shows games or testing-style prompts
- "How much feeling Nuri shows": 1 Calm – 5 Full-hearted (default 3 Warm)
- Optional caregiver dashboard (Hybrid / Managed setup modes) and a
  consent-first Therapist setup mode — what is shared is always disclosed
- Encrypted local backup/restore with a paper recovery phrase
- Bluetooth device memory (10 slots), and a built-in visual puzzle game
  with big targets — no timers, no fail states, no ads, no in-app purchases

## Readability standard (design law)

Senior-facing screens are very easy to read by default: body text ≥ 20sp,
headings ≥ 28sp, nothing senior-facing below 16sp, WCAG AAA contrast target,
dark text on a light uncluttered background. Appearance is adjustable
**within** the standard — font steps Easy / Easier / Easiest (~1.15x / ~1.3x)
and four curated themes (Classic, Soft, Dark, Yellow on black), every one
verified at 7:1 contrast or better — with a live preview in Settings.
Appearance choices are stored on the device and are never reset by an update.

## Updates

Nuri checks this repository's release feed. The default is the simple path:
one quiet notification per new version; you tap to update. **Automatic
updates are an option**: at first install Nuri offers "Update Nuri
automatically — you won't need to do anything when a new version is ready,"
and the same choice lives in Settings (default off). Automatic updates are
verified before installing — the download's SHA-256 fingerprint and signing
certificate must match the installed app, or the update is refused. Updates
never change Nuri's interface or voice.

## License

GPL-3.0-or-later — see `LICENSE` and `THIRD-PARTY-NOTICES` at the repo root.
Third-party components keep their own licenses, listed in the notices file.
