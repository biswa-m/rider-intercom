# Rider Intercom — Opus/Jitter update

This version keeps the existing foreground-service and Android 11 Bluetooth SCO behavior and changes only the network-audio timing path.

## Network playback

- UDP receive callback only validates/queues packets.
- Added `OpusJitterBuffer` with a 2-packet (40 ms) target and 6-packet maximum.
- Added short reorder/loss wait (20 ms).
- Large sequence jumps are treated as VAD silence gaps and resynchronized instead of being counted as hundreds of lost packets.
- Opus decoding and `AudioTrack.write()` run on a dedicated playback coroutine.
- Playback uses a 20 ms cadence without an extra fixed 20 ms delay after `AudioTrack.write()`.
- Playback underruns/loss/late/overflow/resync counters are exposed in diagnostics.

## VAD

- Existing energy/RMS VAD is retained.
- Added 100 ms pre-roll so the start of a word is less likely to be clipped.
- Silence is still not transmitted as a normal Opus voice packet.

## Noise reduction

No explicit `NoiseSuppressor`, `AcousticEchoCanceler`, or `AutomaticGainControl` was added in this step. Capture remains `VOICE_COMMUNICATION`, so device/vendor audio processing may still be applied by Android.


## Latency-focused behavior

- Playback uses a dedicated coroutine, so UDP reception is never blocked by `AudioTrack.write()`.
- Small sequence gaps are waited on briefly and then represented by one 20 ms silent frame.
- Large sequence jumps are treated as VAD silence gaps and resynchronized.
- Jitter target is 40 ms to avoid adding an unnecessarily large fixed delay.
