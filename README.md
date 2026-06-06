<div align="center">

# HailWall

**Server-side mod whitelist / blacklist for Minecraft (Fabric & NeoForge).**
When a player joins, the server checks the mods on their **client** and kicks them
if disallowed mods are present (or required mods are missing).

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-brightgreen)
![Fabric](https://img.shields.io/badge/Fabric%20Loader-0.19.2%2B-blue)
![Java](https://img.shields.io/badge/Java-25-orange)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

> ⚠️ **Install on BOTH the server and every client.** A purely server-side mod cannot read a
> client's full mod list (vanilla never sends it). Like every mod of this kind, HailWall ships a
> tiny companion that runs on the client. It's a single jar — put it on both sides.

A modern, lightweight reimplementation of the classic **[Mod Whitelist](https://github.com/Viola-Siemens/Mod-Whitelist)**
idea, rebuilt for the new 26.1 toolchain (Mojang mappings, Fabric Loom 1.16, Java 25).

## Project structure

HailWall is a multi-loader, multi-version mod. The shared logic lives **once** in `core/`; each
target is a self-contained Gradle build under `versions/<mc-version>/<loader>/` that compiles that
shared code against the right Minecraft / loader APIs.

```
core/                    shared mod logic (config, networking, verifier, access log)
versions/
  1.16.5/fabric/         Fabric — Minecraft 1.16.5   (JDK 8)
  1.18.2/fabric/         Fabric — Minecraft 1.18.2   (JDK 17)
  1.19.2/fabric/         Fabric — Minecraft 1.19.2   (JDK 17)
  1.20.1/fabric/         Fabric — Minecraft 1.20.1   (JDK 17)
  1.21.1/fabric/         Fabric — Minecraft 1.21.1   (JDK 21)
  26.1.2/fabric/         Fabric — Minecraft 26.1.2   (JDK 25)
  1.21.1/neoforge/       NeoForge — Minecraft 1.21.1 (JDK 21)
modrinth/description.md  store-page text  (publishing material, not mod code)
PUBLISHING.md            release checklist (publishing material, not mod code)
```

## Features

- **Whitelist mode** (default) — block everything except the mods you allow. `minecraft`, `fabric-api`, the loader, `java` and `hailwall` are always allowed.
- **Blacklist mode** — allow everything except specific mods (cheat clients, x-ray, …).
- **Required mods** — kick players missing a mandatory mod.
- **Operator bypass** (`operatorsBypass`) — operators are never kicked, even with extra mods.
- **Transition / monitor mode** (`enforce: false`) — kick nobody, but still log everyone's mods so you can build your list without disrupting players.
- **Access log** — one JSON-Lines file per day under `config/hailwall/` (reachable even on Aternos), auto-pruned after `accessLogRetentionDays`.
- **Signed handshake** — HMAC + per-connection challenge against trivial spoofing.
- **No gameplay overhead** — all work happens once at login; the log is written off-thread.

## How it works

During the login phase the server sends a query on the `hailwall:modlist` channel with a random
challenge. The client answers with its top-level mods (id + version), signed with HMAC. The server
verifies the signature, evaluates the list against the config, and allows or disconnects the player
— all before they enter the world. No mixins into gameplay, no client GUI, just the Fabric login
networking API plus one small accessor mixin.

| Situation | Result |
|---|---|
| Installed on client **and** server | ✅ Full verification |
| Installed only on the **client** | Plays fine elsewhere; protects nothing here |
| Installed only on the **server** | A client without HailWall can't join (if `requireCompanionMod`) |

## Installation

1. Install **Fabric Loader 0.19.2+** and **Fabric API** (for 26.1.2) on server and client.
2. Put `hailwall-x.y.z.jar` in the `mods/` folder of the **server** and of **every client**.
3. Start the server once to generate `config/hailwall.json`, edit it, restart.

## Configuration — `config/hailwall.json`

```jsonc
{
  "mode": "whitelist",          // "whitelist" or "blacklist"
  "enforce": true,              // false = monitor mode (log only, kick nobody)
  "operatorsBypass": true,      // operators are never kicked
  "requireCompanionMod": true,  // kick clients without HailWall
  "verifySignature": true,
  "logModLists": true,
  "enableAccessLog": true,
  "accessLogRetentionDays": 5,

  "whitelist": ["sodium", "iris", "modmenu"],          // YOUR allowed mods
  "blacklist": ["meteor-client", "wurst", "baritone"], // used only in blacklist mode
  "requiredMods": [],

  "messageForbidden": "Kicked by HailWall: disallowed mods detected:",
  "messageMissingRequired": "Kicked by HailWall: required mods are missing:",
  "messageNoCompanion": "This server requires the HailWall mod. Please install it (client-side) to join.",
  "messageTampered": "Mod verification failed: invalid signature.",
  "messageProtocol": "Incompatible HailWall protocol version: please update the mod."
}
```

Mod ids are the `id` field of each mod's `fabric.mod.json`. The easiest way to collect them is the
**access log** (`config/hailwall/access-YYYY-MM-DD.jsonl`) or the client's `logs/latest.log`, where
HailWall prints the reported mod list.

## Privacy & data

The client sends **only mod ids + versions** of top-level mods — no file paths, no system info, no
personal data. It's used solely to allow/deny the join and is recorded only in the server's own
access log (default 5-day retention). Other players can't see it. Open source, so you can verify it.

## Security note (honest)

Like **every** client-side mod check, HailWall is not bul