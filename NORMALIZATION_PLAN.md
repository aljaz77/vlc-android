# VLC for Android — Volume Normalization & Car Playback Improvements

Working plan for the `volume-normalization` branch of `aljaz77/vlc-android`.

Goal: music played through Android Auto in the car should sit at a consistent
volume from track to track, configurable from its own settings screen, with
several notions of "normal" and several ways of getting there.

---

## Findings from the existing codebase

These are verified, not assumed:

| Question | Answer |
| --- | --- |
| Does VLC-Android already have any normalization? | Partially. ReplayGain exists (`KEY_AUDIO_REPLAY_GAIN_*`, `VLCOptions.kt:235`), buried at the bottom of Audio settings. It only works on files that already carry ReplayGain tags. |
| Are the libVLC audio filters we need actually compiled into the shipped Android binary? | **Yes.** Grepped `libvlc.so` from `org.videolan.android:libvlc-all:3.7.6`: `compressor-*`, `normvol`, `norm-buff-size`, `norm-max-level`, `gain-value` and the ReplayGain options are all present. |
| Which audio output is used? | **AudioTrack** (and OpenSLES). There is no `aaudio` module in 3.7.6 despite `AOUT_AAUDIO` existing in Kotlin. |
| Can Android audio effects attach to VLC's output? | **Yes.** `VLCOptions.kt:175` passes `--audiotrack-session-id`, and `PlaybackService.kt:961` already broadcasts that session to external equalizers. |
| Is Android Auto search wired up? | Mostly. `PlaybackService.onSearch` → `MediaSessionBrowser.search`, `onPlayFromSearch` + `VoiceSearchParams`, and `BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED` is advertised in `onGetRoot`. Needs hardening, not building from scratch. |
| Can we build without compiling native VLC? | **Yes.** The `debug` flavor pulls prebuilt `libvlc-all:3.7.6` + `medialibrary-all:0.13.23` from Maven. Only the `dev` flavor needs the NDK. |

---

## Phase 0 — Toolchain and a baseline build

Nothing is touched until a stock build works.

1. Temurin JDK 17, Android SDK platform 36 + build-tools 36.0.0, Gradle 9.3.1
   (version taken from `buildsystem/compile.sh:313`; this repo ships no wrapper).
2. Create `local.properties` — `build.gradle:76` loads it unconditionally and
   the build fails without it.
3. `settings.gradle` includes `:libvlcjni:libvlc`, which is a separate VideoLAN
   repo not present here. Drop that include so the prebuilt-AAR `debug` flavor
   resolves.
4. `assembleDebug` → confirm an installable APK from unmodified sources.
5. Branch `volume-normalization`.

**Exit criteria:** an APK built from untouched upstream code.

---

## Phase 1 — Normalization core

New package `org.videolan.vlc.audio.normalization`.

### `NormalizationSettings.kt`
Typed accessor over SharedPreferences. Two independent axes, which is what
"multiple modes for what it considers normal and how it achieves it" asks for:

**What counts as "normal" (target):**

| Profile | Target | For |
| --- | --- | --- |
| Quiet | −23 LUFS | broadcast reference, night listening |
| Standard | −18 LUFS | ReplayGain's own reference |
| Streaming | −14 LUFS | matches Spotify/YouTube |
| Car | −11 LUFS | loud, compressed, cuts through road noise |
| Custom | user dB | manual |

**How it gets there (method):**

| Method | Engine | Live? |
| --- | --- | --- |
| ReplayGain tags | libVLC | restart |
| Measured loudness (Phase 3) | Android effect, per track | live |
| Dynamic compression | libVLC `compressor` | restart |
| Volume leveler | libVLC `normvol` | restart |
| Device limiter | `DynamicsProcessing` / `LoudnessEnhancer` | live |
| Auto | measured → tags → compressor fallback chain | mixed |

Plus: strength, peak limiting on/off, preamp, apply-to-video toggle.

### libVLC engine — additions to `VLCOptions.libOptions`
- ReplayGain methods → `--audio-replay-gain-mode/-preamp/-default/-peak-protection`,
  derived from the chosen target rather than typed in by hand.
- Compression → `--audio-filter=compressor` plus `--compressor-rms-peak`,
  `-attack`, `-release`, `-threshold`, `-ratio`, `-knee`, `-makeup-gain`,
  all derived from target + strength.
- Volume leveler → `--audio-filter=normvol` plus `--norm-buff-size`, `--norm-max-level`.

The existing hand-edited ReplayGain preferences keep working; the new screen
writes the same underlying keys so there is one source of truth, not two.

### Android effect engine — `DeviceNormalizer.kt`
Attaches to `VLCOptions.audiotrackSessionId`:
- `DynamicsProcessing` (API 28+) — limiter stage, optional single-band compressor.
- `LoudnessEnhancer` (API 19+) — target gain, and the fallback when
  DynamicsProcessing is missing or throws.
- Lifecycle tied to `PlaybackService`, released when normalization is off.
- **Changes apply live**, with no interruption to the playing track.

### Restart behaviour
libVLC-backed options need `VLCInstance.restart()` + `restartMediaPlayer()` —
the same pattern the existing ReplayGain prefs already use
(`PreferencesAudio.kt:173`). The settings screen will say plainly which
settings interrupt playback and which don't.

---

## Phase 2 — Its own settings screen

- `res/xml/preferences_normalization.xml` + `PreferencesNormalization.kt`.
- Entry point: a `PreferenceScreen` in `preferences_audio.xml`, wired through
  `PreferencesAudio.onPreferenceTreeClick` → `loadFragment(...)`, following the
  existing `PreferencesCasting` / `PreferencesAudioControls` pattern.
- Registered in `PreferenceParser` so in-app settings search finds it.
- Strings in `application/resources/.../values/strings.xml`.
- TV variant (`application/television/.../PreferencesAudio.kt`) gets the same
  entry if it's cheap; otherwise TV keeps the existing ReplayGain block.

---

## Phase 3 — Per-track loudness analysis

This is the part that actually fixes "every song is a different volume",
because it does not depend on tags anyone remembered to write.

- **Storage:** Room entity `TrackLoudness(mediaId, uri, integratedLufs,
  truePeakDb, analyzedAt, version)` + DAO in `application/mediadb`, with a
  database version bump and migration.
- **Analyzer:** `MediaExtractor` + `MediaCodec` decode to PCM, then EBU R128
  (ITU-R BS.1770-4): K-weighting biquad pair, 400 ms gated blocks, absolute
  gate at −70 LUFS, relative gate at −10 LU, plus sample-peak tracking.
  Hardware decode runs far faster than realtime.
- **Scheduling:** a background worker constrained to charging + idle,
  batched, cancellable, with visible progress.
- **Application:** on track start, gain = target − measured, clamped, and
  reduced further if true peak would clip. Applied through `LoudnessEnhancer`,
  so it's per-track and instant.
- **Fallback chain:** measured → ReplayGain tags → the selected realtime method.

---

## Phase 4 — Android Auto

Normalization lives in `PlaybackService`, so car playback is covered by
construction. Beyond that:

- Add a normalization toggle as a media-session custom action in the AA
  playback view, next to the existing shuffle/repeat ones
  (`MediaSessionCallback.onCustomAction:233`). Backed by the Android-effect
  engine specifically so it toggles instantly rather than restarting libVLC
  mid-drive.
- Verify the whole path end to end rather than assuming it.

---

## Phase 5 — Voice search in the car ("play <track> on VLC")

Hardening what exists:

- Confirm the `MEDIA_PLAY_FROM_SEARCH` intent filter is declared — Assistant
  needs it to route to VLC at all.
- Loosen song matching: accent and case folding, partial matches, and
  combined "play X by Y" queries. `vsp.isSongFocus` currently relies on a
  fairly literal `searchMedia`.
- When one specific track is asked for, play *that track* — today an
  unmatched query can fall through to shuffling the whole library.
- Add `onPrepareFromSearch`.
- Keep results inside Assistant's response timeout.

---

## Phase 6 — Clicking a track keeps the queue

New preference with three behaviours:
- Replace queue (today's behaviour)
- Play now, keep queue
- Play now when shuffling or inside a playlist ← your default

Implementation: `PlaybackService.playNow(media)` → `insertNext(listOf(media))`
then `playIndex(currentIndex + 1)`; if the track is already in the queue, jump
to its existing index instead of duplicating it. Because `insertNext` inserts
into the already-shuffled `mediaList` at `currentIndex + 1`
(`PlaylistManager.kt:1079`), the rest of the shuffle queue survives untouched.

Hooked into the `MediaUtils.openList` call sites in `BaseAudioBrowser`,
`AudioBrowserFragment`, `HeaderMediaListActivity`, `PlaylistFragment` and
`BaseBrowserFragment`, and into `MediaSessionCallback.onPlayFromMediaId` so it
behaves the same in the car.

---

## Known risks

- **Car testing is yours.** I can build and reason about the code; I cannot
  drive it. Android Auto behaviour needs real verification on your head unit.
- **`DynamicsProcessing` quality varies by device** — hence LoudnessEnhancer
  as a fallback and libVLC filters as an output-agnostic alternative.
- **libVLC option changes interrupt playback.** Mitigated by routing the
  in-car toggle through the Android-effect engine.
- **DB migration** needs care so nobody's library breaks.
- **The `dev` flavor stays out of reach** — it compiles libVLC from source and
  needs the NDK plus a Linux toolchain. Everything here targets `debug`/`release`
  against the published AARs.
