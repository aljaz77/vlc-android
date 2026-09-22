# Volume normalization and car playback changes

What this branch adds to VLC for Android, why, and what still needs testing on
real hardware.

Built and verified to compile against the `debug` flavour (prebuilt
`libvlc-all:3.7.6` and `medialibrary-all:0.13.23` from Maven, no NDK needed).

---

## Why

Music files are mastered at wildly different levels. Played back through Android
Auto, where the volume knob is not somewhere you want to be reaching, that means
constantly riding the volume. VLC already shipped Replay Gain, but it is buried
at the bottom of Audio settings and does nothing at all for files that carry no
Replay Gain tags, which is most of them.

---

## What was verified before building anything

| Question | Answer |
| --- | --- |
| Are the libVLC audio filters actually in the shipped Android binary? | **Yes.** `libvlc.so` from `libvlc-all:3.7.6` contains `compressor-*`, `normvol`, `norm-buff-size`, `norm-max-level`, `gain-value` and the Replay Gain options. |
| Which audio output is used? | **AudioTrack** (and OpenSLES). There is no `aaudio` module in 3.7.6, despite `AOUT_AAUDIO` existing in the Kotlin. |
| Can Android audio effects attach to VLC's output? | **Yes.** `VLCOptions` already passes `--audiotrack-session-id`, and `PlaybackService` already broadcasts that session to external equalizers. |

---

## Volume normalization

Settings → Audio → Volume normalization. Two independent choices.

### What counts as normal

| Profile | Target | For |
| --- | --- | --- |
| Quiet | −23 LUFS | broadcast reference, night listening |
| Standard | −18 LUFS | the reference Replay Gain is built around |
| Streaming | −14 LUFS | matches Spotify, YouTube, Apple Music |
| Car | −11 LUFS | loud and dense, cuts through road noise |
| Custom | −30..−5 LUFS | manual |

### How it gets there

| Method | Engine | Changes apply |
| --- | --- | --- |
| Automatic | measured gain + Replay Gain + gentle compressor | mixed |
| Replay Gain tags | libVLC | on libVLC rebuild |
| Measured loudness | Android audio effect, per track | live |
| Dynamic compression | libVLC `compressor` | on libVLC rebuild |
| Volume leveler | libVLC `normvol` | on libVLC rebuild |
| Device limiter | `DynamicsProcessing` / `LoudnessEnhancer` | live |

Plus strength, peak limiting, a maximum boost ceiling, album mode, and whether
to apply any of it to video.

libVLC only reads its audio filter configuration when the instance is created.
The settings screen compares the *derived option lists* before and after a
change and rebuilds libVLC only when they actually differ, so changing a purely
device-side setting never interrupts playback. The manual Replay Gain controls
are greyed out while normalization is on, so there is one source of truth rather
than two contradictory ones.

### Loudness analysis

The part that removes the dependency on tags entirely.

`LoudnessMeter` implements ITU-R BS.1770-4 with EBU R128 gating: K-weighting
biquads derived per sample rate, 400 ms blocks at 75 % overlap, then absolute
(−70 LUFS) and relative (−10 LU) gating so fades and quiet passages do not drag
the result down. `LoudnessAnalyzer` drives it from `MediaCodec`, which decodes
far faster than realtime.

Results go in a Room table keyed by URI, so a library rescan does not throw them
away. Analysis is opportunistic: the current and next tracks are queued as they
play, so the library converges on being fully measured as it is listened to.
Settings also offers a full scan and a way to discard the results.

**Conformance:** checked against the EBU Tech 3341 test signals. A stereo 1 kHz
sine at −23 dBFS measures −23.0 LUFS to within **0.017 LU**; mono reads 3 dB
below stereo; the result is independent of sample rate.

---

## Android Auto

Normalization applies in the car by construction, because it lives in
`PlaybackService`.

Beyond that there is a normalization switch in the car's playback view, next to
shuffle and repeat, with a preference under Android Auto settings to hide it.

It is only offered for methods backed by a device effect, because only those can
be switched while a track is playing. Toggling a libVLC filter needs the
instance rebuilt, and `PlaylistManager.restart` stops playback and reloads the
saved playlist — not something to do to someone who is driving. A button that
silently did nothing would be worse than no button.

---

## Voice search: "play &lt;track&gt;"

`onPlayFromSearch` never looked at the tracks its own search returned. The
unstructured fallback — which is what a voice assistant sends most of the time —
checked albums, then artists, then playlists, then genres, and gave up.
`SearchAggregate.tracks` was sitting right there unused, so asking for a song by
name played its album, or its artist, or nothing.

`VoiceSearchMatcher` now ranks candidates by how well they answer the query
rather than trusting a substring match:

- case, accents and punctuation folded away before comparing
- the artist used to disambiguate when the assistant supplies one
- a shorter title beats a longer one, so "Alive" beats "Alive (Remastered 2011)"
- the query is read both as a whole title and as "&lt;title&gt; by &lt;artist&gt;",
  and the better reading wins — otherwise "stand by me" becomes a search for
  "stand" by an artist called "me"

The car's search results screen uses the same ordering and promotes tracks above
albums and artists when one is a strong match. Both sides share the ordering, so
the position carried in a media id still refers to the track that was tapped.

Asking for one specific song also no longer shuffles.

---

## Tapping a track keeps the queue

Tapping one track used to replace the whole queue. During a shuffle-all session
or partway through a playlist that throws away everything lined up, from a single
tap, with no undo.

Settings → Audio → Tapping a track:

- **Plays now, keeps the queue** (new default)
- **Plays now, keeps a shuffled queue** — only while shuffling
- **Replaces the queue** — the original behaviour

`PlaybackService.playNow` inserts the track directly after the current one and
skips to it, so whatever was coming next still comes next. Because the insert
goes into the already shuffled list rather than regenerating it, a shuffle queue
survives intact. A track already in the queue is jumped to rather than
duplicated. Applies in Android Auto's track list too.

Multi-track actions — play all, play album, play playlist — still replace the
queue, since that is what they explicitly ask for.

---

## Still needs testing on real hardware

- **Everything in the car.** Android Auto behaviour on a real head unit is not
  something that can be verified from a build machine.
- **`DynamicsProcessing` quality varies by device.** `LoudnessEnhancer` is the
  fallback, and the libVLC filters are an output-agnostic alternative if the
  device effects disappoint.
- **The per-media video override** (`:audio-filter=` and
  `:audio-replay-gain-mode=none` for video when "apply to video" is off) is the
  one place relying on libVLC accepting an empty per-media option value. If it
  does not, the failure mode is that video gets normalized too.
- **Analysis on low-end devices** runs a second decoder alongside playback. It is
  limited to one track at a time and can be switched off.

## Build notes

- `settings.gradle` and two `build.gradle` files now only wire up
  `:libvlcjni:libvlc` and `:medialibrary` when that separate checkout is present,
  so the project configures without the NDK.
- `:application:vlc-android`'s unit test source set does not compile on this
  branch, and did not before it either: it references helpers from a
  `test-common` directory that is not in the tree. `LoudnessMeterTest` was run
  against an equivalent standalone JVM harness.
