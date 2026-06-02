# HailWall — Mod Whitelist for Fabric

**HailWall** checks the mods installed on a player's **client** when they join, and kicks them if they have **disallowed** mods (or are missing a **required** one). It's a modern, lightweight take on the classic *Mod Whitelist* idea, built for **Minecraft 26.1.2 / Fabric**.

> ⚠️ **Install it on BOTH the server and every client.** A purely server-side mod cannot read a client's full mod list — vanilla simply doesn't send it. So, like every mod of this kind, HailWall needs a tiny companion running on the client.

## ✨ Features

- **Whitelist mode** (default) — block everything except the mods you allow. The essentials (`minecraft`, `fabric-api`, the loader, `java`, `hailwall`) are always allowed.
- **Blacklist mode** — allow everything except specific mods (cheat clients, x-ray, …).
- **Required mods** — kick players who are *missing* a mandatory mod.
- **Operator bypass** — server operators are never kicked, even with extra mods (like a `usePermission` toggle).
- **Transition / monitor mode** (`enforce: false`) — kick nobody, but still record everyone's mods. Perfect for rolling HailWall out without disrupting players while you build your list.
- **Access log** — one JSON-Lines file per day (kept under `config/`, so it's reachable even on hosts like Aternos) listing who joined and with which mods. Old files are pruned automatically.
- **Signed handshake** — HMAC + per-connection challenge to stop trivial packet spoofing.
- **Zero gameplay overhead** — everything happens once, during login; the log is written off-thread. No per-tick cost.

## ⚙️ How it works

During the login handshake the server asks the client for its mod list. The client replies with its installed **top-level** mods (id + version), signed with HMAC. The server verifies the signature, compares the list against your config, and either lets the player in or disconnects them — all **before** they enter the world.

| Situation | Result |
|---|---|
| Installed on client **and** server | ✅ Full verification |
| Installed only on the **client** | You can still play on other servers; here it protects nothing |
| Installed only on the **server** | A client without HailWall can't join (when `requireCompanionMod` is on) |

## 📦 Setup

1. Install **Fabric Loader 0.19.2+** and **Fabric API** (for 26.1.2) on the server and on every client.
2. Drop the `hailwall-x.y.z.jar` into the `mods/` folder of the **server** and of **each client**.
3. Start the server once to generate `config/hailwall.json`, edit it, and restart.

## 🔧 Configuration — `config/hailwall.json`

```jsonc
{
  "mode": "whitelist",          // "whitelist" or "blacklist"
  "enforce": true,              // false = monitor mode (log only, kick nobody)
  "operatorsBypass": true,      // operators are never kicked
  "requireCompanionMod": true,  // kick clients that don't have HailWall
  "verifySignature": true,
  "logModLists": true,
  "enableAccessLog": true,
  "accessLogRetentionDays": 5,

  "whitelist": ["sodium", "iris", "modmenu"],          // YOUR allowed mods
  "blacklist": ["meteor-client", "wurst", "baritone"], // used only in blacklist mode
  "requiredMods": []
  // + fully customizable / translatable kick messages
}
```

Find any mod's id in its `fabric.mod.json` (the `id` field). Easier: read it straight from the **access log**, or from the client's `logs/latest.log`, where HailWall prints the reported mod list.

## 🔒 Privacy & data

- The client sends **only mod ids + versions** of top-level mods — **no** file paths, **no** system info, **no** personal data.
- That data is used to allow/deny the join and is recorded **only in the server's own access log** (default 5-day retention). It's never sent anywhere else, and other players can't see it.
- **Open source** — you can verify exactly what it does.

## 🛡️ Honest security note

Like **every** client-side mod check, HailWall is not bulletproof: a determined user could decompile the jar and forge a clean mod list. The HMAC + challenge stops casual spoofing, not an expert. It works great against normal players (who won't decompile anything) and is best paired with `online-mode=true`. Treat it as one solid layer, not a silver bullet.

## 📋 Requirements

Minecraft **26.1.2** · Fabric Loader **0.19.2+** · **Fabric API** · Java **25**

## 🙏 Credits

Inspired by **[Mod Whitelist](https://modrinth.com/mod/mod-whitelist)** by Viola-Siemens (not affiliated). Licensed under **MIT**.
