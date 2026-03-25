# Procedural Terrain Smoothing (PTS)

**Minecraft 1.21.1** • **NeoForge** • **Java 21**

PTS dynamically generates slab variants for terrain blocks at runtime. Using exhaustive JSON model introspection, it mathematically slices geometries and UV coordinates to produce bottom, top, and double slabs that are visually and behaviorally identical to their source blocks. All blockstate properties, tags, and multipart definitions are cloned transparently. The mod integrates during NeoForge’s pack evaluation phase with no race conditions.

## Features

- Full runtime generation of accurate slab models and blockstates
- Mathematical UV and geometry slicing for perfect visual parity
- Complete cloning of blockstate properties, loot tables, and tags
- Dynamic resource pack creation (client and server)
- Worldgen feature that inserts slabs at terrain edges and corners for smoother surfaces
- Support for all dimensions (Overworld, Nether, End)
- Configurable targeting via keywords, namespaces, or exact block IDs
- Automatic color handler delegation for tinted blocks
- Falling block and waterlogging behavior inherited from target blocks

## Use Cases

- Creating smoother, less blocky terrain transitions in vanilla and modded worlds
- Enhancing realism in custom terrain generation pipelines
- Providing slab variants for resource packs or data packs without manual model authoring
- Maintaining full compatibility with existing mods that rely on standard block tags and properties

## Inspiration

This mod was heavily inspired by the [Terrain Slabs mod](https://github.com/Coun7ered/terrain-slabs-mod). Because the original implementation is Fabric-only and not data-driven, PTS was developed from the ground up for NeoForge with a fully dynamic, runtime-based architecture.

## Configuration

After the first launch, edit `config/pts-common.toml`:

```toml
edge_conversion_percent = 95
generate_corners = true

keywords = [
  "dirt", "grass", "sand", "sandstone", "terracotta", "stone", "gravel", "mud",
  "podzol", "mycelium", "deepslate", "netherrack", "basalt", "blackstone", "soul",
  "nylium", "wart", "end_stone", "ice", "prismarine", "moss", "ash", "peat",
  "silt", "chalk", "argillite", "travertine", "chert", "kaolin", "prismoss", "salt"
]

namespaces = []
exact_blocks = []
```

Changes to the configuration automatically trigger regeneration of the runtime asset pack on the next game start.

## Building from Source

The project requires both data generation and compilation steps:

1. Clone the repository
2. Run data generation:
   ```bash
   ./gradlew runData
   ```
3. Build the mod:
   ```bash
   ./gradlew build
   ```

The compiled jar will be located in `build/libs/`.

## Installation

1. Install Minecraft 1.21.1 with NeoForge.
2. Place the PTS jar in the `mods` folder.
3. Launch the game. The mod will generate its runtime assets on first load.

No additional dependencies are required beyond NeoForge.

## Compatibility

PTS is designed to work alongside other mods that add or modify terrain blocks. Blocks registered by PTS automatically inherit mineable tags and any tags present on the original block at registration time.

---

**License:** MIT (see LICENSE file)
