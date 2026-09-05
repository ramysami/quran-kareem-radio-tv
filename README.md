# Quran Kareem Radio TV — إذاعة القرآن الكريم

An Android TV app that streams **إذاعة القرآن الكريم من القاهرة** (Quran Kareem Radio, Cairo).

Built and verified on a real Android TV (Xiaomi Mi TV, Android 10 / API 29, 1280×720).
## Screenshots
![screenshot_1](screenshot_1.png)

![screenshot_2](screenshot_2.jpg)
## Install

Go to [Releases](https://github.com/ramysami/quran-kareem-radio-tv/releases).

## Features

- **Live playback** of the station with automatic retry (5 attempts, backing off) when a
  live stream drops out.
- **Play / Pause / Stop** as three large D-pad targets. Stop fully tears the stream down;
  Pause keeps the session alive.
- **Sleep timer** with 5 / 15 / 30 minute and 1 hour presets plus a custom value
  (1–600 minutes). A live countdown shows under the status line, and the timer keeps
  running while you're in Settings or have left the app.
- **No in-app volume control** — deliberately left to the remote's own volume keys.
- **Full remote support**: D-pad navigation, and the transport keys
  (PLAY / PAUSE / PLAY_PAUSE / STOP) work through a MediaSession even when the app is
  in the background. MENU opens Settings.
- **Arabic and English** — the UI follows whatever language the TV is set to.
- **Surah recognition (optional)** — a "Which Surah?" button beside Play listens for thirty
  seconds and shows the Surah and Ayah being recited, using a speech model that runs on the
  device. Off by default; turning it on in Settings offers the one-time model download
  (about 125 MB). It can listen to the radio stream itself, or through the microphone for
  recitation playing nearby. Nothing leaves the device. See
  [Surah recognition](#surah-recognition).

## Settings

Reachable from the **Settings** button or the remote's **MENU** key. Everything persists
across restarts and reboots.

| Setting | Notes |
| --- | --- |
| Change stream URL | For when the default stream goes down. Must be `http://` or `https://`. |
| Restore default stream URL | Back to the station's HLS stream on webvideocore.net. |
| Background: Default image | The supplied artwork. |
| Background: No background image | Plain deep-blue gradient. |
| Background: My own image | Pick a file on the device, or enter an image URL. |
| Restore default background | Background only. |
| Identify the Surah being recited | Turns the feature (and the button) on or off. Offers the model download the first time. Android 7.0 and later. |
| Listen to the radio stream / through the microphone | Where the button listens. The microphone asks for its permission. |
| Keep the answer on screen for | 30 s to 15 min (default 2 min). |
| Recognition model | Download with progress, or remove the model. |
| Restore all default settings | Stream URL *and* background, after a confirmation. |

Remote images are cached on disk, so a custom background from a URL still appears when
the network is down.

## Surah recognition

Everything runs on the device. Pressing **Which Surah?** collects thirty seconds of audio —
from a tap on ExoPlayer's own audio pipeline, so no microphone permission is involved, or
from the microphone if that was chosen — and transcribes it with
[Whisper](https://github.com/openai/whisper) fine-tuned on Quran recitation by
[Tarteel AI](https://huggingface.co/tarteel-ai/whisper-base-ar-quran), run with ONNX Runtime
from the [ONNX export](https://huggingface.co/eventhorizon0/tarteel-ai-onnx-whisper-base-ar-quran)
(Apache 2.0, about 125 MB, downloaded on request). A halfway pass at fifteen seconds gives
an early answer that the full pass confirms or corrects.

The transcript is matched against the full text of the Quran, bundled from the
[Tanzil Project](https://tanzil.net): every pair of consecutive words is indexed to the
Ayah it starts in, weighted by rarity, and the Ayah that gathers the most evidence by a
clear margin wins. A position found in the last ten minutes biases the next answer toward
the Ayahs just after it. The answer — the Surah name always in Arabic, and the Ayah number —
stays on screen for the hold time from Settings, with a button that opens that Ayah in the
Quran app through its `quran://surah/ayah` link (quran.com in a browser if no app handles it).

The model is loaded on the first press and released when the player screen is left. A
thirty-second transcription takes a few seconds on a recent phone and longer on a
television box; the button counts down while it listens and says "One moment…" while it
decodes. ONNX Runtime needs Android 7.0, so the feature is not offered below that; the
radio itself still runs on 5.0.

## Layout notes

The controls sit at the **left-centre**, clear of the mosque and lantern in the artwork.
The root layout is pinned to `ltr` on purpose — the artwork's empty area is on the left,
so the panel must stay there even when the TV is set to Arabic. Text still shapes
right-to-left normally.

When the default artwork is showing, the app hides its own station title: the artwork
already carries the wordmark. Switch to "no background" or a custom image and the title
appears, with the panel recentred vertically.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 34).

```bash
./gradlew :app:assembleDebug
```

If `JAVA_HOME` isn't set, Android Studio's bundled JDK works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Project layout

| File | Role |
| --- | --- |
| `MainActivity.kt` | Player UI, remote key handling, sleep-timer dialogs |
| `SettingsActivity.kt` | Settings list, stream URL and background pickers |
| `PlaybackService.kt` | ExoPlayer + MediaSession, retry logic, foreground playback |
| `Prefs.kt` | Persisted settings and their defaults |
| `SleepTimer.kt` | Process-wide countdown, independent of the Activity |
| `BackgroundLoader.kt` | Resolves default / none / custom backgrounds, with caching |
| `recognition/Recitation.kt` | Runs the recogniser while the player is on screen; holds the current Surah |
| `recognition/QuranIndex.kt` | Matches recognised words to a Surah and Ayah (unit-tested) |
| `recognition/SpeechEngine.kt` | Whisper on a worker thread, handed windows of 16 kHz PCM |
| `recognition/WhisperModel.kt` | Encoder / decoder / decoder-with-past on ONNX Runtime, greedy decoding |
| `recognition/WhisperFeatures.kt` | Log-mel spectrogram, with an exact 400-point FFT |
| `recognition/WhisperTokenizer.kt` | Byte-level BPE decoding of Whisper's token ids |
| `recognition/RadioAudioTap.kt` | Copies ExoPlayer's decoded audio for the recogniser |
| `recognition/MicrophoneSource.kt` | The microphone as an alternative source |
| `recognition/ModelStore.kt` | Downloads and tracks the speech model's files |

Minimum Android 5.0 (API 21), targets API 34.
