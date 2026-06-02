# Changelog

All notable changes to HailWall are documented here.

## 1.0.3
- First public release.
- Default kick messages are now in English (still fully configurable / translatable).
- Added a project & in-game mod icon.
- Polished metadata for publishing (name, description, links).

## 1.0.2
- Operator-bypass console line no longer prints the offending mod names.
- Access-log writes moved fully off the server thread (dedicated daemon thread) — zero disk I/O on the main thread.
- Malformed login payloads are now rejected gracefully (no uncaught exceptions).

## 1.0.1
- Fixed operator bypass: the real player name is now read from the authenticated `GameProfile` via an accessor mixin (in 26.1 `getUserName()` returns a decorated `"name (uuid)"` string that never matched operator names).

## 1.0.0
- Initial build for Minecraft 26.1.2 / Fabric.
- Login-time client mod verification: whitelist / blacklist / required mods.
- Operator bypass, transition (monitor) mode, per-day access log with retention.
- HMAC-signed handshake with per-connection challenge.
