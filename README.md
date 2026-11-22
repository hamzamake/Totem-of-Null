# Totem of Null

A Fabric mod for Minecraft 1.21.11. It adds one cursed totem that cheats death, erases your enemies, and commands an army of Null Soldiers.

**Download:** [CurseForge: Totem of Null](https://www.curseforge.com/minecraft/mc-mods/totem-of-null-from-unstable-smp)

Requires [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and [Carpet](https://www.curseforge.com/minecraft/mc-mods/carpet). Install on both the server and the client.

## Features

- **The Totem of Null:** saves you from death. When it pops, every player on the hit list falls into the void, and every Null Soldier vanishes.
- **Only one can exist:** copies crumble. Get it by renaming a Totem of Undying to `Totem of Null` in an anvil (20 levels) or with `/null give`.
- **Null Soldiers:** up to 20 Carpet fake players that step out of a nether portal, with pure black skins, enchanted netherite armour with the Flow trim, an enchanted sword, tools, the Null shield and golden apples.
- **Orders:** follow, attack a mob up to 64 blocks away, hold a spot, or mine every block of a kind nearby, with the loot going straight to you.
- **Smart combat:** they defend you, hit back, time their hits for criticals, circle targets, dodge creepers and eat golden apples when low.
- **Real walking:** they path around walls and only teleport to follow you into another dimension.
- **Netherrack bridge:** build a bridge to wherever you look.

## Controls

| Action | Totem in… | What it does |
|---|---|---|
| Sneak + right-click | Off hand | Summon Null Soldiers |
| Sneak + right-click | Main hand | Orders menu |
| Sneak + left-click | Off hand | Send them at the mob / to the spot you look at; again to call them back |
| Sneak + left-click | Main hand | Netherrack bridge |

Commands: `/null orders`, `/null order <follow|attack|mine|summon|dismiss>`. For ops: `/null give`, `/null tag|untag <player>`, `/null soldiers spawn|clear`, and `/null config` (opens a settings window).

## Building

Gradle needs Java 25; the mod targets Java 21.

```
./gradlew build
```

The jar is written to `build/libs/`.
