# Rider Intercom - Next milestone

This update adds the first VAD (voice activity detection) layer without changing the current audio loopback behavior.

## VAD v1

- 16 kHz mono PCM
- 20 ms analysis frames
- 500 ms startup noise-floor calibration
- adaptive energy threshold
- 240 ms speech hangover to prevent choppy speech detection
- no external/native dependency
- VAD is observational only in this milestone; it does **not** mute or alter the loopback audio

The UI shows:

- `Speech detected` when VAD considers the microphone active
- `Silence / listening` otherwise

## Background lifecycle remains

- screen off / locked: audio continues
- app in background: audio continues
- swipe app task away from Recents: service stops
- service uses `START_NOT_STICKY`

## Test

1. Connect V8.
2. Select V8 Bluetooth SCO.
3. Start audio.
4. Wait about 1 second without speaking so the VAD can calibrate ambient noise.
5. Speak normally and watch `Voice activity: Speech detected`.
6. Stop speaking and confirm it returns to `Silence / listening` after a short delay.
7. Lock the phone and confirm the audio service remains active.

The audio path is still loopback at this stage. The next networking milestone will use the same captured PCM frames and VAD decision to decide when voice packets should be transmitted.
