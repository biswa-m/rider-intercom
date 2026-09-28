# Rider Intercom - Nearby Connections Test Branch

This branch is a connectivity/transport lab for Google Nearby Connections. It intentionally does not contain the production audio pipeline.

## Current transport tests

### Synchronized 10-second byte test

Start the test on one connected phone only. The runner coordinates both phones:

`PREPARE -> READY -> START(delay) -> DATA -> drain -> STOP -> RESULT`

The receiver automatically records sequence integrity and inter-arrival timing. No button is required on the receiving phone.

Default test:

- 640-byte payload
- 50 packets/sec
- 10 seconds
- 1.5-second coordinated start delay

### Voice-sized test

- 80-byte payload
- 50 packets/sec
- 10 seconds

This approximates the size/rate range of a small Opus voice frame stream while retaining the same transport diagnostics.

### Rate sweep

Runs four independent 10-second tests at:

- 10 pps
- 25 pps
- 50 pps
- 100 pps

Each uses 640-byte payloads.

## Receiver diagnostics

Each synchronized test records:

- transmitted packets
- received packets
- unique packets
- duplicates
- out-of-order packets
- observed sequence gaps
- transmitted/received bytes
- average inter-arrival time
- P95 inter-arrival time
- maximum inter-arrival time
- maximum received sequence
- approximate received throughput

The sender waits briefly after transmission before requesting the receiver result so already-submitted Nearby payloads have time to arrive.

## Connection behavior

Nearby Connections remains responsible for the connection layer. Auto Connect remains enabled and is intentionally left unchanged while transport testing is being completed.

Manual peer Connect and any STATUS_OUT_OF_ORDER_API_CALL issue are deliberately not part of this transport-test change.

## Unified CSV

All completed tests are stored in the existing shared CSV buffer.

- **Export Unified CSV** exports the buffer.
- **Reset** clears the buffer.


## Nearby callback timing instrumentation

Receiver packet traces now record both the timestamp at entry to `PayloadCallback.onPayloadReceived()` and the timestamp when the packet trace record is created. The CSV also includes callback-to-callback inter-arrival and callback processing duration. This is intended to determine whether observed burstiness is already present at Nearby callback delivery or is introduced by application-side processing.
