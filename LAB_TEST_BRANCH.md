# Rider Intercom — Test Branch

This branch is deliberately rebuilt from the network layer upward.

## Rules

- Existing production intercom code is not used.
- Every layer gets its own package and files.
- Runtime tests are automated and produce measurable results.
- A later layer must not hide failures in an earlier layer.
- Production architecture is created only after the test layers are proven.

## Current layer

### Layer 1 — Wi-Fi Direct + UDP dummy data

1. Grant Wi-Fi Direct permissions.
2. Discover the other phone.
3. Connect using Android Wi-Fi Direct.
4. Both phones press `Run 10-second test`.
5. A small TCP control handshake synchronizes the two phones.
6. Both phones send 50 UDP packets/sec for 10 seconds.
7. Each phone receives and records packets.
8. Sequence integrity and arrival timing are measured.
9. Results are exportable as one combined CSV.

No microphone, AudioRecord, AudioTrack, Opus, VAD, or jitter buffer is involved.

## Future layers

1. Wi-Fi Direct
2. UDP dummy data
3. UDP protocol / sequencing
4. Synthetic PCM transport
5. Opus encode transport
6. Opus decode
7. Audio capture
8. Audio playback
9. Jitter buffer
10. Full intercom

Each future layer should add new files and tests rather than modifying older proven layers unless a deliberate protocol change is required.


## Layer 1.5 — Wi-Fi Direct connection lifecycle
The lab includes an automated 3-cycle explicit disconnect/reconnect test. It measures disconnect and reconnect completion time and retries transient Wi-Fi Direct BUSY/ERROR responses. Physical out-of-range recovery remains a manual test because it requires moving devices; the app should later record loss detection and reconnection when that scenario is exercised.
