# Privacy

PlankRS connects to `https://api.plankrs.com` to send enabled clan notifications to the PlankRS Discord server.

The plugin may send the logged-in RuneScape name and enabled events such as drops, level-ups, quests, collection log entries, and combat achievements. It also sends basic connection information such as plugin version and queued event count.

## Clan chat

Clan chat relay is disabled by default. Enabling it opens a confirmation dialog explaining that visible clan messages may be forwarded to the PlankRS backend and clan Discord, including sender names and message text. This can include messages written by other clan members.

Messages beginning with `!` or `/` are not relayed, and Discord mentions are neutralized.

## Screenshots

Screenshots are disabled by default. Enabling them opens a confirmation dialog before the setting is applied. When enabled, supported events may include a RuneLite screenshot. The image may contain chat, overlays, nearby player names, or other interface content visible at that time.

## Storage

The backend stores member registration, rank information, and the hashed plugin installation key. Event payloads, clan chat, and screenshots are forwarded to Discord without being stored in PostgreSQL.

Pending events may remain temporarily in the local RuneLite queue until delivery succeeds. Posted Discord content is retained for a set time until deleted or by request from users.

Questions or deletion requests can be directed to https://discord.gg/plankrs