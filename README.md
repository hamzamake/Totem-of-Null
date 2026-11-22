# Totem of Null (NeoForge 1.21.1)

A NeoForge mod for Minecraft 1.21.1. It adds one cursed totem that cheats death, erases your enemies, and commands an army of Null Soldiers.

**Download:** [CurseForge: Totem of Null](https://www.curseforge.com/minecraft/mc-mods/totem-of-null-from-unstable-smp)

Needs nothing but NeoForge (no Carpet). Install it on both the server and the client.

The Fabric 1.21.11 version is on the [`main`](https://github.com/hamzamake/Totem-of-Null-from-unstable-smp/tree/main) branch.

## Features

- **The Totem of Null:** saves you from death. When it pops, every player on the hit list falls into the void, and every Null Soldier vanishes.
- **Only one can exist:** while someone has it, nobody can make another, and copies crumble. Get it by renaming a Totem of Undying to `Totem of Null` in an anvil (20 levels), or with `/null give`.
- **Null Soldiers:** up to 20 fake players that step out of a nether portal. They have pure black skins and enchanted netherite armour with the Flow trim, and carry an enchanted sword, tools, the Null shield and golden apples.
- **Orders:**
  - follow you
  - attack a mob up to 64 blocks away
  - hold a spot
  - mine every block of a kind nearby, with the loot going straight to you
- **Smart combat:** they defend you, hit back, time their hits for criticals, circle targets, dodge creepers and eat golden apples when low.
- **Real walking:** they walk in a ring around you, path around walls, pillar out of pits with netherrack, and only teleport to follow you into another dimension.
- **Netherrack bridge:** a soldier builds a bridge to wherever you look, block by block.

## Controls

| Action | Totem in… | What it does |
|---|---|---|
| Sneak + right-click | Off hand | Summon Null Soldiers |
| Sneak + right-click | Main hand | Orders menu |
| Sneak + left-click | Off hand | Send them at the mob / to the spot you look at; again to call them back |
| Sneak + left-click | Main hand | A soldier builds a netherrack bridge |

**Commands:**
- **Anyone:** `/null orders` and `/null order <follow|attack|mine|bridge|summon|dismiss>`.
- **Ops:** `/null give`, `/null tag|untag <player>`, `/null soldiers spawn|clear`, and `/null config`, which opens a settings window.

## Building

Gradle needs Java 21.

```
./gradlew build
```

The jar is written to `build/libs/`.
