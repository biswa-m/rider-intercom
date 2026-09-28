# Future Jitter Module Boundary

This baseline intentionally contains **no jitter-buffer implementation**.

The transport layer exposes a stable boundary for a future higher-level packet-timing module:

```text
Google Nearby Connections
        ↓
NearbyConnectionManager
        ↓
PeerTransport / NearbyTransport
        ↓
PayloadListener
        ↓
[future jitter module]
```

Existing connection, discovery, lifecycle, and test-runner behavior remains unchanged.

A future jitter module should depend on `PeerTransport` / `PayloadListener` rather than reaching directly into `NearbyConnectionManager` or the Google Nearby API.

`ReceivedPayload.receivedAtNs` captures the callback-boundary timestamp so later timing logic does not need to modify the Nearby callback implementation.
