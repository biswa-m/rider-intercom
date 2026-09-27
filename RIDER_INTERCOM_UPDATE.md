# Rider Intercom - Network Audio Baseline

## Current test mode
The default network configuration intentionally disables all optional processing:

- VAD: OFF
- Opus: OFF
- Jitter buffer: OFF
- VAD pre-roll: OFF

The active network path is:

`16 kHz mono PCM -> UDP -> 16 kHz mono PCM -> AudioTrack`

This is the baseline used to isolate the current audio-quality/latency problem.

## Configurable network features
The UI exposes session-level switches for:

- VAD
- Opus
- Jitter buffer (available with Opus)
- VAD pre-roll (available with VAD)

The settings apply when the intercom is started. Stop the intercom before changing them, then start it again. Both phones should use the same settings.

## Important project files preserved
This update is built from the user's latest source and preserves existing project files, including Git/Gradle wrapper files such as `.gitignore`, `app/.gitignore`, `app/proguard-rules.pro`, `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.
