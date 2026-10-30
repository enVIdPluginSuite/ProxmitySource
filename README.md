# ProxmitySource

**by enVId Tech** · package `dev.etran.proxmitysource` · Kotlin 2.2.21 · Gradle

Place a sound source anywhere and ProxmitySource works out, from the blocks around it, how that sound
actually travels: it spreads through open air, leaks through doors and windows, is muffled by thick walls,
and echoes more in caves and enclosed rooms than in open fields. Sounds can be triggered by players,
redstone, mobs, or the clock, and you can use your own uploaded sounds.

- Runs on Paper / Spigot **1.20.1 through 26.3** (one jar). Java: 17 (1.20.1-1.20.4), 21 (1.20.5-1.21.11), 25 (26.x).
- Built against the 1.20.1 API using only stable Bukkit API and plain string sound keys.

## Build

    ./gradlew build        # needs JDK 17 or newer; runs the unit tests too
    # -> build/libs/ProxmitySource-1.0.0.jar   (drop into plugins/)

## Quick start: a clock tower bell

1. Look at the bell block and run `/proxsource create tower 96 0.6 0.3`
   (id, radius in blocks, echoiness 0-1, penetration 0-1; all optional after the id).
2. The plugin bakes the tower's sound profile in the background (`/proxsource info tower` shows progress).
3. `/proxsource trigger tower add TIME`, `/proxsource hours tower 6,12,18`, `/proxsource set tower toll-by-hour true`
4. `/proxsource play tower` to hear it; walk around and `/proxsource probe tower` to see how loud it is where you
   stand and how much the walls add.

## The three knobs

| Knob | Meaning |
|---|---|
| `radius` | Distance (blocks) at which the sound has faded to nothing. |
| `echoiness` | 0 = no echo, 1 = very echoey. The echo you actually hear also depends on how enclosed your surroundings are. |
| `penetration` | 0 = walls stop sound, 1 = sound passes through walls as if they were air. |

## How the "wall detection" works

Baking a source (`/proxsource bake <id>`, automatic on create) does this:

1. **Copy blocks:** chunks within the radius are copied a few per tick (`profile.chunks-per-tick`), so there is no lag spike.
   Existing but unloaded chunks may be loaded from disk (`profile.load-unloaded-chunks`); never-generated chunks are never created.
2. **Proximity flood (off the main thread):** a Dijkstra-style search spreads outward from the source through the
   26 neighbouring blocks. Moving through air costs distance. Entering a solid block costs extra:
   `1 + wall-cost x (1 - penetration) x material factor`. The cheapest route wins, so sound finds doorways and
   windows, goes around corners, and is cut down by thick walls. Material factors are in
   `profile.wall-materials` (wool absorbs more, glass, leaves, water, doors much less).
3. **Echo field:** 14 rays per region measure how enclosed it is and how big the space is.
4. **Save:** the result is stored compactly in `plugins/ProxmitySource/profiles/<id>.prx`. Playing a sound
   afterwards is just a lookup per listener, with no world scanning.

Profiles re-bake automatically when you change the source's radius/penetration or the profile settings, and
(if `profile.auto-rebake` is on) a while after blocks are placed or broken near a source, so a freshly built wall
changes the sound after things settle. The server never bakes more than one source at a time.

Measured bake times for the flood itself (open air = worst case, run off the main thread):
radius 64 ≈ 0.3 s, radius 128 ≈ 3 s. Memory while baking is about (2 x radius)³ x 3 bytes
(radius 64 ≈ 6 MB, radius 128 ≈ 50 MB) plus the chunk copies; `profile.max-radius` (default 128) caps it.

## Moving and cloning

`/proxsource move <id>` relocates a source to where you look (or stand) and re-bakes it. `/proxsource clone <id> <newId>`
copies every setting (triggers, filters, hours, amplification, ...) to where you look/stand as a new source with its own
freshly baked profile; delete the original afterwards if you were moving it.

## Amplification and falloff

`amplification` (0.1 - 8, default 1) makes a source louder than a normal Minecraft sound can be. Minecraft caps any single
sound at 100%, so the plugin layers extra identical copies (`settings.max-layers`, default 4, limits this). It also keeps the
sound at full volume further out before fading (amplification 2 = full volume until about half the radius), but never beyond
`radius`. `falloff` (0.1 - 8) reshapes the curve: below 1 the sound carries further before fading, above 1 it drops off faster
up close. Both can be set per source: `/proxsource set tower amplification 3`, `/proxsource set tower falloff 0.6`.
Stacking copies uses extra sound channels on each client, so keep amplification modest on busy servers.

## Triggers

Each source has a `trigger-radius` (default 6 blocks); triggers look at that sphere around it.

| Trigger | Fires when |
|---|---|
| PLAYER_ENTER / PLAYER_EXIT | a player comes within / leaves the trigger radius |
| PLAYER_INTERACT | a player right-clicks a block within it (filter: `/proxsource filter <id> material add BELL`) |
| ENTITY_ENTER | a mob comes within it (filter: `/proxsource filter <id> entity add ZOMBIE`; none = any living mob) |
| REDSTONE | a signal switches on within it |
| TIME | the in-game clock hits an hour (`/proxsource hours`) |
| TIMER | automatically every `frequency-seconds` of real time (see below) |

## Frequency: "every X, Y times, Z seconds apart"

Add the TIMER trigger and set three properties:

    /proxsource trigger tower add TIMER
    /proxsource set tower frequency-seconds 600      # X: every 10 minutes
    /proxsource set tower tolls 3                    # Y: ring 3 times
    /proxsource set tower toll-interval-seconds 4    # Z: 4 seconds apart

The timer ignores the cooldown (the frequency is the rhythm), counts from when the plugin or a reload starts
(it never rings right at reload), and runs whether or not anyone is nearby; only players in range hear it.
Defaults for all three are under `defaults:` in config.yml.

## Sound, echo, tolling

Each listener hears the source at the loudness the profile gives their position, arriving slightly later the farther
away they are (`settings.propagation-delay`). Echoes: count = echoiness x `echo-max-count`, each `echo-decay` times
quieter and `echo-pitch-shift` lower, strength and spacing depending on the enclosure and size of the listener's own
surroundings. Tolling repeats the whole thing `tolls` times, `toll-interval-seconds` apart (`toll-by-hour` tolls the clock hour, 1-12).

All settings live in `config.yml` and can be overridden per source with `/proxsource set <id> <property> <value>`
(`default` clears the override). Edit the global config in-game with `/proxsource config list|get|set`;
apply file edits with `/proxsource reload`.

## Custom sounds

Minecraft can only play sounds the player's client already has, so custom sounds need a resource pack. ProxmitySource builds it:

1. Put `.ogg` (Vorbis) files in `plugins/ProxmitySource/sounds/`, or run `/proxsource sound add <name> <direct-url>`.
   **Use mono files**: stereo sounds do not fade with distance in Minecraft (a warning is shown).
   Convert: `ffmpeg -i in.wav -ac 1 -c:a libvorbis -q:a 5 toll.ogg`
2. Whenever the folder changes (checked every `sounds.auto-detect-seconds`, default 10) or you add a sound with
   `/proxsource sound add`, both packs are rebuilt automatically: `proxmitysource-pack.zip` (Java) and
   `proxmitysource-bedrock.mcpack` (Bedrock). `/proxsource reload` does the same on demand.
3. `/proxsource set tower sound toll`

### Java players (optional pack)

Host the zip yourself and set `resource-pack.url`, or set `resource-pack.host.enabled: true` with `host.port` and
`host.public-address` so the plugin hosts it. Players are then **offered** the pack when they join
(`resource-pack.offer-on-join`) and again whenever it changes (`offer-on-update`). It is never forced: they get
Minecraft's normal accept/decline prompt. Declining is remembered (`pack-optout.yml`), so they are not nagged.
Players can run `/proxsource pack get` to fetch it later or `/proxsource pack optout` to toggle the automatic offer
(permission `proxmitysource.pack`, default everyone). Admins: `/proxsource pack send [player|all]`.

Before 1.20.3 a player can have only one server resource pack; if you already use one, merge `assets/proxmitysource/` into it.

### Bedrock players (GeyserMC)

Bedrock clients only receive resource packs while connecting, and the Java prompt does not reach them, so Geyser has
to hand the pack out. Pick one:

- **URL (recommended):** make `proxmitysource-bedrock.mcpack` reachable by direct link (with `resource-pack.host.enabled`
  it is served at `http://<public-address>:<port>/proxmitysource-bedrock.mcpack`), then add it to Geyser's `config.yml`
  under `resource-pack-urls`. Keep `force-resource-packs` off in Geyser if you want it optional.
- **File:** set `bedrock.auto-copy-to-geyser: true` (and `bedrock.geyser-packs-folder` if Geyser is not in
  `plugins/Geyser-Spigot/packs`), and the plugin copies the pack there on every rebuild.

After a sound is added, Bedrock players must reconnect, and usually fully restart the game, to pick up the new version.
`/proxsource pack info` shows the Bedrock file and link.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.
