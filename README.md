# ProxyTools

Lightweight proxy utilities for **BungeeCord** and **Velocity** – one jar, both platforms.

## Features

- **Custom MOTD**
  - multiple MOTDs, picked randomly, cycled in order or pinned to the first one (`static`), sticky for a few seconds instead of changing on every single ping (`motd.mode`, `motd.interval-seconds`)
  - `&` colors, `&#RRGGBB` hex, `<gradient:#RRGGBB:#RRGGBB>text</gradient>` and `<rainbow>text</rainbow>`
  - `%online%` / `%max%` placeholders, custom max player count
  - hover text on the player count, either custom lines or the real online players (`hover.mode: custom|players`)
  - a custom server icon (favicon), picked up live without a reload
  - `/proxytools motd` shows the currently active MOTD in chat, so you can check it without opening the server list
- **Maintenance mode** – `/maintenance on [duration] [reason]|off|schedule|status`
  - optional reason: `/maintenance on 30m Database update` shows the reason in the kick screen, `/maintenance status` and the maintenance MOTD via `%reason%`; it also works with `schedule <delay> <duration> [reason]`
  - players with bypass get a short chat notice when they join during maintenance
  - optional timer: `/maintenance on 10m` auto-disables after the duration, with configurable warning broadcasts before it ends (`maintenance.timer-warnings`). Refuses to silently replace a timer that's already running — `/maintenance off` first if you want a different duration
  - schedule ahead: `/maintenance schedule 30m 10m` starts maintenance automatically in 30 minutes and runs it for 10, with warning broadcasts counting down to the *start* (`maintenance.schedule-warnings`); `/maintenance schedule cancel` aborts a pending window
  - custom MOTD, version text, hover text and icon in the server list while active
  - blocks joins and kicks online players without bypass — or, if `maintenance.redirect-server` names a registered backend, sends them there instead (covers new joins, reconnects and attempts to `/server` away while it's on); left empty (the default) it's a normal network kick
  - editable feedback for every action, including how many players were kicked and time remaining (`%duration%`)
  - state, whitelist, a running timer and a pending schedule all survive proxy restarts (`data.yml`)
- **UUID-based whitelist** – entries are matched by UUID, never by name, so a name change (or someone else claiming an old name) can never let the wrong account bypass maintenance. Adding an offline player by name stores it as *pending*; it's linked to their UUID automatically the next time they join.
- **Announcements** (`announcements` in `config.yml`, off by default) – automatic chat messages to everyone online, in order or random, every `interval-seconds`, with an optional prefix. A message can be a single line or a nested list for several lines. Nothing is sent while nobody is online.
- **Team chat** – `/teamchat <message>` (alias `/tc`) reaches every online player with `proxytools.teamchat` across all backend servers, and the console. The format is editable in the language file (`%player%`, `%server%`, `%message%`). Can be turned off with `teamchat.enabled: false` (on by default).
- **Slot reservation** (`slots` in `config.yml`, off by default) – the plugin enforces its own player limit and keeps the last `reserved` slots for players with `proxytools.slots.reserved` (VIPs). Players with `proxytools.slots.bypass` (admins) always get in, even above the limit. The server list shows the limit as max players. On BungeeCord set `player_limit: -1` in its own `config.yml`, otherwise BungeeCord kicks before ProxyTools can let admins in.
- A `maintenance.redirect-server` that isn't a registered server is reported in the console on start and reload.
- **`/broadcast` (alias `/bc`)** – sends a colored message to everyone on the network. Works purely at the proxy level, independent of whatever the backend Spigot/Paper servers have.
- **Multi-language messages** – English by default, German built in, switch with `language: de` in `config.yml`. Add your own by copying `lang/en.yml` to `lang/<code>.yml` and translating it.
- Reload everything with `/proxytools reload`, without restarting the proxy.
- Optional [bStats](https://bstats.org) usage statistics.

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/maintenance <on [duration] [reason]\|off>` | `proxytools.maintenance` | Control maintenance mode |
| `/maintenance schedule <delay> <duration> [reason]\|cancel` | `proxytools.maintenance` | Schedule (or cancel) a future maintenance window |
| `/maintenance status` | `proxytools.maintenance` or `.status` | View maintenance status only |
| `/maintenance whitelist <add\|remove\|list> [player\|uuid]` | `proxytools.maintenance.whitelist` | Manage the maintenance whitelist |
| `/broadcast <message>` (alias `/bc`) | `proxytools.broadcast` | Message everyone on the network |
| `/teamchat <message>` (alias `/tc`) | `proxytools.teamchat` | Write to, and read, the team chat |
| `/proxytools [info\|motd\|reload]` (alias `/pt`) | `proxytools.reload` for `reload` | Version info / MOTD preview / reload configuration |
| – | `proxytools.maintenance.bypass` | Allowed to join during maintenance |
| – | `proxytools.slots.reserved` | May use the reserved slots |
| – | `proxytools.slots.bypass` | Always allowed to join, even when the network is full |

The console always has all permissions. A duration looks like `10m`, `1h30m` or `45s` (in that unit order: d, h, m, s). In `/maintenance on`, an argument starting with a digit is read as the duration, everything else as the reason.

## Server icon

Drop a 64×64 `icon.png` into the plugin's data folder to use it as the server list icon. An optional
`icon-maintenance.png` is shown instead while maintenance mode is on (falls back to `icon.png` if it
doesn't exist). Both files are re-read automatically when changed — no reload needed.

## bStats

ProxyTools reports anonymous usage statistics via [bStats](https://bstats.org):
[BungeeCord dashboard](https://bstats.org/plugin/bungeecord/ProxyTools/34376) ·
[Velocity dashboard](https://bstats.org/plugin/velocity/ProxyTools/34377). Since bStats treats
every platform as its own project, the plugin IDs (set as `BSTATS_PLUGIN_ID` at the top of
[`ProxyToolsBungee.kt`](src/main/kotlin/de/doetchen/projects/proxytools/bungee/ProxyToolsBungee.kt)
and
[`ProxyToolsVelocity.kt`](src/main/kotlin/de/doetchen/projects/proxytools/velocity/ProxyToolsVelocity.kt))
differ between the two.

What's sent: bStats' standard platform data (Java/OS version, player count bracket, online-mode,
...) plus two extra pies this plugin adds — the configured `language` and whether `maintenance` is
currently enabled — nothing server-identifying. Server admins can always opt out globally via
`plugins/bStats/config.yml` on their proxy.

## Requirements

|  | Supported |
|---|---|
| BungeeCord (and forks such as Waterfall) | API 1.21+ |
| Velocity | 3.4+ (Java 17+) |

Java 17 or newer.

## Updating config.yml across versions

`config.yml` carries a `config-version` field. If a future update renames or restructures a
setting, append a migration step to
[`ConfigMigrator.kt`](src/main/kotlin/de/doetchen/projects/proxytools/core/ConfigMigrator.kt) and set
`config-version` in the bundled `config.yml` to the new step count + 1. Existing installs are then
migrated in place on their next start instead of silently falling back to the bundled default.
Running a step rewrites `config.yml` without its comments. Users should not edit `config-version`.

## Building

```bash
./gradlew build
```

The finished plugin is `build/libs/ProxyTools-<version>.jar`.

## Project layout

```
src/main/kotlin/de/doetchen/projects/proxytools
├── core      platform independent logic (config, language, maintenance, timers, MOTD, commands)
├── bungee    BungeeCord adapter  (bungee.yml)
└── velocity  Velocity adapter    (velocity-plugin.json)

src/main/resources
├── config.yml   settings: language, MOTD, maintenance
└── lang/        one file per language (en.yml, de.yml, ...), all user-facing text
```

At runtime, the plugin's data folder gets `config.yml`, `lang/<code>.yml` and `data.yml` (maintenance
state, timer and whitelist) — all editable, and reloadable without a restart.

Kotlin stdlib, SnakeYAML and bStats are bundled and relocated to `de.doetchen.projects.proxytools.libs`, so they cannot clash with other plugins.
