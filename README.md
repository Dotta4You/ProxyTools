# ProxyTools – The All-in-One Proxy Plugin 🛠️

ProxyTools bundles the everyday tools of a Minecraft network into one lightweight plugin for **BungeeCord** and **Velocity**: MOTD, maintenance mode, hub, private messages, team chat and more. One jar and every module can be switched off.

---

## 🚀 Features

- **Custom MOTD:** Several MOTDs with colors, hex, gradients and rainbow text, hover text, custom server icon
- **Maintenance Mode:** Timer, scheduling, own MOTD, UUID whitelist, survives restarts
- **Hub Command:** `/hub` sends players to the first lobby or the one with the fewest players
- **Private Messages:** `/msg`, `/reply`, `/ignore`, `/msgtoggle` and staff-only `/socialspy`
- **Team Chat:** Network-wide staff chat with join/leave alerts
- **Broadcast & Announcements:** Manual `/broadcast` or automatic chat messages
- **Slot Reservation:** Own player limit with reserved slots for "VIPs"
- **Remember Last Server:** Players rejoin where they left off
- **Own Info Commands:** Create `/discord`, `/vote` & co. directly in the config
- **Multi-Language Support:** English and German built in, but you can add your own

---

## 🎮 Commands

- `/maintenance on [duration] [reason]` – Enable maintenance, optionally timed and with a reason
- `/maintenance off` – Disable maintenance
- `/maintenance schedule <delay> <duration> [reason]` – Schedule a maintenance window (`cancel` aborts it)
- `/maintenance status` – Show the current state
- `/maintenance whitelist <add|remove|list> [player|uuid]` – Manage the whitelist
- `/hub` *(aliases `/lobby`, `/l`)* – Go to a lobby
- `/msg <player> <message>` *(aliases `/tell`, `/w`, `/whisper`, `/m`)*, `/reply <message>` *(alias `/r`)* – Private messages
- `/ignore <player|list>` – Ignore a player
- `/msgtoggle` *(alias `/togglemsg`)* – Stop all private messages
- `/socialspy [on|off]` *(alias `/spy`)* – See other players' private messages
- `/teamchat <message>` *(alias `/tc`)* – Write to the team chat
- `/broadcast <message>` *(alias `/bc`)* – Message the whole network
- `/proxytools [info|motd|update|reload]` *(alias `/pt`)* – Version info, MOTD preview, update check, reload

Durations look like `10m`, `1h30m` or `45s`.

---

## 🔐 Permissions

| Permission | Description |
|------------|-------------|
| `proxytools.maintenance` | Use `/maintenance` |
| `proxytools.maintenance.status` | View the maintenance status only |
| `proxytools.maintenance.whitelist` | Manage the maintenance whitelist |
| `proxytools.maintenance.bypass` | Join during maintenance |
| `proxytools.broadcast` | Use `/broadcast` |
| `proxytools.teamchat` | Use and read the team chat, see team alerts |
| `proxytools.socialspy` | Use `/socialspy` |
| `proxytools.msg.bypass` | Private messages get through ignore and message switch |
| `proxytools.slots.reserved` | Use the reserved slots |
| `proxytools.slots.bypass` | Always join, even when the network is full |
| `proxytools.motd` | Use `/proxytools motd` |
| `proxytools.update` | Use `/proxytools update` and get told about new versions when joining |
| `proxytools.reload` | Use `/proxytools reload` |

`/hub`, `/msg`, `/reply`, `/ignore`, `/msgtoggle` and `/proxytools info` need no permission.

---

## 📥 Installation

**Requirements:** BungeeCord (or Waterfall) API 1.21+ or Velocity 3.4+, Java 17+

1. Put the jar into the `plugins` folder of your **proxy** (not a backend server)
2. Restart the proxy
3. Edit `plugins/ProxyTools/config.yml` and run `/proxytools reload`

**Server icon:** Put a 64×64 PNG named `default.png` (and optionally `maintenance.png`) into `plugins/ProxyTools/icons/`.

**BungeeCord + slot reservation:** Set `player_limit: -1` in BungeeCord's own `config.yml`, otherwise BungeeCord kicks players before ProxyTools can let admins through.

---

## 🗄️ Storage

Last server, ignore lists and the message switch are stored in:

| `storage.type` | Use it for |
|----------------|------------|
| `yaml` | A single proxy, no setup |
| `h2` | A single proxy with many players, no database server needed |

An existing `players.yml` is imported into H2 once when you switch. If the H2 file can't be opened, the plugin falls back to the YAML file and keeps working. Maintenance state and whitelist are always kept in two small YAML files (`data/maintenance.yml` and `data/whitelist.yml`).

---

## 💬 Support

- **Support & Issues:** [GitHub Issues](https://github.com/Dotta4You/ProxyTools/issues)
- **Ideas & Suggestions:** [GitHub Discussions](https://github.com/Dotta4You/ProxyTools/discussions/categories/ideas)

---

## 🔔 Update checker

The plugin checks the [GitHub releases](https://github.com/Dotta4You/ProxyTools/releases) for a newer version when it starts and every 12 hours after that. A new version is announced once in the console. Players with `proxytools.update` get a clickable message a moment after joining, and joining also refreshes a check that is older than an hour. `/proxytools update` checks right away. Nothing is downloaded or installed, and only the version number is read. Set `update-checker.enabled: false` in the `config.yml` to turn it off, or `update-checker.download-url` to point admins to your download page instead of GitHub.

---

## 📊 Metrics

- Uses **bStats** for anonymous usage statistics ([BungeeCord](https://bstats.org/plugin/bungeecord/ProxyTools/34376) · [Velocity](https://bstats.org/plugin/velocity/ProxyTools/34377)). Turn it off with `bstats: false` in the `config.yml` or globally in `plugins/bStats/config.yml`.

---

✨ Made with ❤️ by **Dötchen**
