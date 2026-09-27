# Rider Intercom - networking milestone

This update adds the first two-phone networking path.

## Current background lifecycle

- screen off / locked: audio continues
- app in background: audio continues
- swipe app task away from Recents: service stops
- service uses `START_NOT_STICKY`

## Two-phone Wi-Fi test

The first network milestone intentionally uses **uncompressed 16 kHz mono PCM over UDP**.

This is not the final network codec. It exists to validate:

- two-phone connectivity
- microphone capture
- VAD-gated transmission
- UDP packet flow
- remote playback through Bluetooth SCO
- basic end-to-end latency

### Packet flow

Phone A:

`Mic -> 20 ms PCM -> VAD -> UDP -> Phone B`

Phone B:

`UDP -> PCM -> AudioTrack -> Bluetooth SCO`

Both phones transmit and receive at the same time.

## Test setup

1. Connect both phones to the same Wi-Fi network, or connect both through a suitable phone hotspot.
2. On each phone, open Rider Intercom.
3. Note each phone's displayed IPv4 address.
4. Connect the V8 headset and select **Bluetooth SCO**.
5. On Phone A, enter Phone B's IPv4 address and start **Two-Phone Intercom**.
6. On Phone B, enter Phone A's IPv4 address and start **Two-Phone Intercom**.
7. Speak on one phone and verify the other phone plays the voice through V8.
8. Repeat in the opposite direction.
9. Test with the screen locked.
10. Test by swiping the app away from Recents.

## Why raw PCM first

Raw PCM creates a large amount of traffic (about 256 kbps for 16 kHz mono 16-bit audio), so it is not appropriate for the final motorcycle intercom. Once transport is proven, replace the packet payload with Opus to reduce bandwidth and battery usage.

## Next milestone

Replace `PcmVoicePacket` with an Opus packet codec while keeping the UDP transport and audio/VAD boundaries intact.
