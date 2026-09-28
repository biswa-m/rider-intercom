# Jitter Buffer Test Plan

This branch tests only the layers up to jitter buffering. Audio, Opus, AudioRecord and AudioTrack are intentionally excluded.

## Layers

Nearby Connections → NearbyTransport → PacketProtocol → JitterBuffer

## Folder separation

- `lab/transport/`: transport abstraction and Nearby adapter
- `lab/protocol/`: application packet format
- `lab/jitter/`: jitter buffer, deterministic simulation and real Nearby jitter test
- `lab/nearby/`: Google Nearby connection manager and existing connectivity tests

## Tests

1. Deterministic jitter simulation: normal timing, random jitter, reordering, loss, burst loss, duplicates.
2. Real Nearby jitter: 500 packets at 20 ms intervals, 80-byte payload, 40 ms target buffer and 200 ms maximum buffer.

The unified CSV remains the single export buffer.
