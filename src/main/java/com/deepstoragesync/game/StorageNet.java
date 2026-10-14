package com.deepstoragesync.game;

import com.deepstoragesync.DeepStorageSync;
import com.deepstoragesync.core.ContextSorter;
import com.deepstoragesync.core.GlobalRegistry;
import com.deepstoragesync.core.ItemKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Glue between the pure-Java core and the live game. Reads/writes happen against the
 * registry snapshot; real containers are only touched while their chunk is loaded.
 * Only "plain" stacks (no custom name, enchantments, damage, etc.) are tracked.
 */
public final class StorageNet {

    public static final ContextSorter SORTER = new ContextSorter();

    private record Loc(String dim, BlockPos pos) { }

    /** loaded=false means "chunk not loaded, leave it alone"; container=null with loaded=true means the block is gone. */
    private record Lookup(boolean loaded, Container container) { }

    private static GlobalRegistry registry = new GlobalRegistry();
    private static MinecraftServer server;
    private static Path file;
    private static Map<String, Item> items = new HashMap<>();
    private static int tickCounter;

    private StorageNet() { }

    public static GlobalRegistry registry() {
        return registry;
    }

    // ---- lifecycle ----

    public static void onServerStarted(MinecraftServer s) {
        server = s;
        items = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) items.put(idOf(item), item);
        file = s.getWorldPath(LevelResource.ROOT).resolve("deep_storage_sync.tsv");
        try {
            registry = GlobalRegistry.load(file);
        } catch (IOException | RuntimeException e) {
            DeepStorageSync.LOGGER.error("Could not load storage registry, starting empty", e);
            registry = new GlobalRegistry();
        }
        tickCounter = 0;
        DeepStorageSync.LOGGER.info("Deep Storage Sync: {} linked storages", registry.storageIds().size());
    }

    public static void onServerStopping(MinecraftServer s) {
        save();
        server = null;
    }

    public static void save() {
        if (file == null) return;
        try {
            registry.save(file);
        } catch (IOException e) {
            DeepStorageSync.LOGGER.error("Could not save storage registry", e);
        }
    }

    public static void onTick(MinecraftServer s) {
        if (server == null) return;
        tickCounter++;
        if (tickCounter % 20 == 0) {
            for (String id : registry.storageIds()) syncStorage(id);
        }
        if (tickCounter % 6000 == 0) save();
        if (tickCounter % 24000 == 0) SORTER.decay(0.8);
    }

    // ---- item helpers ----

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static Item itemOf(String id) {
        return items.get(id);
    }

    private static boolean isPlain(ItemStack stack) {
        return stack.getComponentsPatch().isEmpty();
    }

    public static boolean isSupported(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    // ---- storage ids ----

    private static String storageId(ServerLevel level, BlockPos pos) {
        return level.dimension() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Loc parse(String id) {
        int at = id.lastIndexOf('@');
        String[] c = id.substring(at + 1).split(",");
        return new Loc(id.substring(0, at),
                new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
    }

    private static Lookup lookup(String id) {
        if (server == null) return new Lookup(false, null);
        Loc loc = parse(id);
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().toString().equals(loc.dim())) continue;
            if (!level.hasChunkAt(loc.pos())) return new Lookup(false, null);
            BlockEntity be = level.getBlockEntity(loc.pos());
            return new Lookup(true, isSupported(be) ? (Container) be : null);
        }
        return new Lookup(false, null);
    }

    // ---- container operations ----

    private static Map<ItemKey, Long> readContents(Container c) {
        Map<ItemKey, Long> out = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty() && isPlain(st)) out.merge(ItemKey.of(idOf(st.getItem())), (long) st.getCount(), Long::sum);
        }
        return out;
    }

    private static long removeFrom(Container c, Item item, long amount) {
        long left = amount;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !st.is(item) || !isPlain(st)) continue;
            int n = (int) Math.min(left, st.getCount());
            st.shrink(n);
            if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            left -= n;
        }
        c.setChanged();
        return amount - left;
    }

    /** Moves as much of {@code stack} as fits into the container; the stack is shrunk by what moved. */
    private static int insertInto(Container c, ItemStack stack) {
        Item item = stack.getItem();
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !st.is(item) || !isPlain(st) || st.getCount() >= st.getMaxStackSize()) continue;
            int n = Math.min(stack.getCount(), st.getMaxStackSize() - st.getCount());
            st.grow(n);
            stack.shrink(n);
            moved += n;
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            int n = Math.min(stack.getCount(), stack.getMaxStackSize());
            ItemStack placed = new ItemStack(item, n);
            if (!c.canPlaceItem(i, placed)) continue;
            c.setItem(i, placed);
            stack.shrink(n);
            moved += n;
        }
        if (moved > 0) c.setChanged();
        return moved;
    }

    // ---- sync ----

    /** If the storage's chunk is loaded: apply queued removals to the real container, then refresh the snapshot. */
    private static void syncStorage(String id) {
        Lookup lk = lookup(id);
        if (!lk.loaded()) return;
        if (lk.container() == null) {
            registry.disconnect(id);
            return;
        }
        Container c = lk.container();
        registry.drainPending(id).forEach((key, delta) -> {
            Item item = items.get(key.id());
            if (delta < 0 && item != null) removeFrom(c, item, -delta);
        });
        registry.connect(id, c.getContainerSize() * 64L, readContents(c));
    }

    /** Apply queued changes to every storage that is currently loaded. */
    public static void flush() {
        for (String id : registry.pendingStorageIds()) syncStorage(id);
    }

    // ---- player actions ----

    public static String toggleLink(ServerLevel level, BlockPos pos, boolean makeInbox) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!isSupported(be)) return "Link only works on chests, barrels and shulker boxes";
        Container c = (Container) be;
        String id = storageId(level, pos);
        if (makeInbox) {
            if (!registry.isConnected(id)) registry.connect(id, c.getContainerSize() * 64L, readContents(c));
            registry.setInbox(id);
            return "Inbox set: new item types will be sent here";
        }
        if (registry.isConnected(id)) {
            registry.disconnect(id);
            if (id.equals(registry.getInbox())) registry.setInbox(null);
            return "Unlinked from the network";
        }
        registry.connect(id, c.getContainerSize() * 64L, readContents(c));
        return "Linked to the network (" + registry.storageIds().size() + " storages)";
    }

    /** Drop whatever did not fit in the inventory at the player's feet. */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        Containers.dropItemStack(player.level(), player.getX(), player.getY(), player.getZ(), stack);
    }

    /** Pull up to {@code amount} of an item into the player's inventory. Returns how many were taken. */
    public static long pull(ServerPlayer player, ItemKey key, int amount) {
        Item item = items.get(key.id());
        if (item == null) return 0;
        long got = registry.withdraw(key, amount);
        if (got <= 0) return 0;
        SORTER.recordPull(key);
        long left = got;
        while (left > 0) {
            int n = (int) Math.min(left, new ItemStack(item).getMaxStackSize());
            ItemStack give = new ItemStack(item, n);
            left -= n;
            if (!player.getInventory().add(give)) giveOrDrop(player, give);
        }
        flush();
        return got;
    }

    /** Dump the player's main inventory (not the hotbar) into the network. */
    public static String dump(ServerPlayer player) {
        flush();
        Inventory inv = player.getInventory();
        Set<String> touched = new HashSet<>();
        long moved = 0;
        long left = 0;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack st = inv.getItem(slot);
            if (st.isEmpty() || !isPlain(st) || st.is(ModItems.LINKER)) continue;
            ItemKey key = ItemKey.of(idOf(st.getItem()));
            List<String> targets = new ArrayList<>(registry.holders(key));
            String inbox = registry.getInbox();
            if (inbox != null && !targets.contains(inbox)) targets.add(inbox);
            for (String id : targets) {
                Lookup lk = lookup(id);
                if (!lk.loaded() || lk.container() == null) continue;
                int n = insertInto(lk.container(), st);
                if (n > 0) {
                    moved += n;
                    touched.add(id);
                }
                if (st.isEmpty()) break;
            }
            if (st.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
            else left += st.getCount();
        }
        for (String id : touched) syncStorage(id);
        if (moved == 0 && left == 0) return "Nothing to dump";
        String msg = "Dumped " + moved + " items";
        if (left > 0) msg += " (" + left + " had nowhere to go: set an inbox or get closer to your chests)";
        return msg;
    }

    public static String status() {
        int storages = registry.storageIds().size();
        int types = registry.totals().size();
        String inbox = registry.getInbox() == null ? "none" : registry.getInbox();
        return storages + " linked storages, " + types + " item types, inbox: " + inbox;
    }
}
