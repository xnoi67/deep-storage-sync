# Deep Storage Sync

Fabric mod for **Minecraft Java 26.3**: a lag-free storage network that works across dimensions and sorts items by what you are actually doing.

## Status

Early scaffold (0.1.0). The platform-independent core is done and tested; the Minecraft-facing layer (blocks, packets, UI) is next.

| Feature | Core logic | In-game layer |
|---|---|---|
| Global Inventory Registry + lazy sync (no chunk loading) | done | todo |
| Contextual smart sorting + player memory | done | todo |
| One-click dump and routing with overflow chest | done | todo |
| Search bar (`keyword`, `@text`, `/regex/`) | done | todo |
| NBT-agnostic grouping | done | todo |
| Projection UI, ghost crafting | - | todo |

## How the core works (`com.deepstoragesync.core`)

- `GlobalRegistry` keeps a snapshot of every connected storage. Withdrawals and deposits change the snapshot instantly and queue per-storage deltas, which are applied to the real container when its chunk loads (`drainPending`).
- `ContextSorter` ranks hot items first (player memory), then Builder, Redstone, Other, and bulk junk last.
- `DumpRouter` tops up storages that already hold an item, then falls back to the overflow chest.
- `SearchFilter` powers the search bar.

## Build

Requires JDK 25 and Gradle 9.6 (Loom 1.17).

```
gradle build
```

The jar is written to `build/libs/`. GitHub Actions builds it on every push (`.github/workflows/build.yml`).

To add the Gradle wrapper locally: `gradle wrapper --gradle-version 9.6.0`, then commit it.

## License

MIT
