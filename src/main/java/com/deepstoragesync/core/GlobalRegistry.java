package com.deepstoragesync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * World-level registry of every connected storage. Reads and writes hit this
 * snapshot instead of the real containers, so remote chunks never need to be
 * loaded. Changes are queued as per-storage deltas and applied to the real
 * container the next time its chunk loads (see {@link #drainPending}).
 */
public final class GlobalRegistry {

    private static final class Storage {
        long capacity;
        long used;
        final Map<ItemKey, Long> items = new HashMap<>();
        final Map<ItemKey, Long> pending = new HashMap<>();
    }

    private final Map<String, Storage> storages = new TreeMap<>();
    private String inbox;

    /** The overflow ("inbox") storage that receives items no other storage holds yet. May be null. */
    public synchronized String getInbox() {
        return inbox;
    }

    public synchronized void setInbox(String id) {
        this.inbox = id;
    }

    public synchronized List<String> storageIds() {
        return new ArrayList<>(storages.keySet());
    }

    public synchronized List<String> pendingStorageIds() {
        List<String> out = new ArrayList<>();
        storages.forEach((id, s) -> {
            if (!s.pending.isEmpty()) out.add(id);
        });
        return out;
    }

    private static void addDelta(Map<ItemKey, Long> map, ItemKey key, long delta) {
        long v = map.getOrDefault(key, 0L) + delta;
        if (v == 0) map.remove(key);
        else map.put(key, v);
    }

    /** Register a storage or refresh its snapshot from the real container (e.g. when its chunk loads). */
    public synchronized void connect(String id, long capacity, Map<ItemKey, Long> contents) {
        Storage s = new Storage();
        s.capacity = capacity;
        contents.forEach((k, v) -> {
            if (v > 0) {
                s.items.put(k, v);
                s.used += v;
            }
        });
        Storage old = storages.get(id);
        if (old != null) s.pending.putAll(old.pending);
        storages.put(id, s);
    }

    public synchronized void disconnect(String id) {
        storages.remove(id);
    }

    public synchronized boolean isConnected(String id) {
        return storages.containsKey(id);
    }

    public synchronized long free(String id) {
        Storage s = storages.get(id);
        return s == null ? 0 : Math.max(0, s.capacity - s.used);
    }

    public synchronized long total(ItemKey key) {
        long t = 0;
        for (Storage s : storages.values()) t += s.items.getOrDefault(key, 0L);
        return t;
    }

    public synchronized Map<ItemKey, Long> totals() {
        Map<ItemKey, Long> out = new TreeMap<>();
        for (Storage s : storages.values()) s.items.forEach((k, v) -> out.merge(k, v, Long::sum));
        return out;
    }

    /** Storages already holding this item, fullest first. */
    public synchronized List<String> holders(ItemKey key) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Storage> e : storages.entrySet()) {
            if (e.getValue().items.getOrDefault(key, 0L) > 0) ids.add(e.getKey());
        }
        ids.sort((a, b) -> {
            int c = Long.compare(storages.get(b).items.get(key), storages.get(a).items.get(key));
            return c != 0 ? c : a.compareTo(b);
        });
        return ids;
    }

    /** Virtually insert items. Returns how many were accepted (limited by free space). */
    public synchronized long deposit(String id, ItemKey key, long amount) {
        Storage s = storages.get(id);
        if (s == null || amount <= 0) return 0;
        long n = Math.min(amount, Math.max(0, s.capacity - s.used));
        if (n == 0) return 0;
        addDelta(s.items, key, n);
        s.used += n;
        addDelta(s.pending, key, n);
        return n;
    }

    /** Virtually remove items, instantly. Returns how many were actually available. */
    public synchronized long withdraw(ItemKey key, long amount) {
        long remaining = amount;
        for (String id : holders(key)) {
            if (remaining <= 0) break;
            Storage s = storages.get(id);
            long take = Math.min(remaining, s.items.get(key));
            addDelta(s.items, key, -take);
            s.used -= take;
            addDelta(s.pending, key, -take);
            remaining -= take;
        }
        return amount - remaining;
    }

    /** Called when a storage's chunk loads: returns the queued deltas to apply to the real container and clears them. */
    public synchronized Map<ItemKey, Long> drainPending(String id) {
        Storage s = storages.get(id);
        if (s == null) return Map.of();
        Map<ItemKey, Long> out = new HashMap<>(s.pending);
        s.pending.clear();
        return out;
    }

    public synchronized boolean hasPending(String id) {
        Storage s = storages.get(id);
        return s != null && !s.pending.isEmpty();
    }

    // ---- persistence (simple TSV, one file per world) ----

    public synchronized void save(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        if (inbox != null) lines.add("B\t" + inbox);
        storages.forEach((id, s) -> {
            lines.add("S\t" + id + "\t" + s.capacity);
            s.items.forEach((k, v) -> lines.add("I\t" + id + "\t" + k.id() + "\t" + k.variant() + "\t" + v));
            s.pending.forEach((k, v) -> lines.add("P\t" + id + "\t" + k.id() + "\t" + k.variant() + "\t" + v));
        });
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static GlobalRegistry load(Path file) throws IOException {
        GlobalRegistry r = new GlobalRegistry();
        if (!Files.exists(file)) return r;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            switch (p[0]) {
                case "B" -> r.inbox = p[1];
                case "S" -> {
                    Storage s = new Storage();
                    s.capacity = Long.parseLong(p[2]);
                    r.storages.put(p[1], s);
                }
                case "I" -> {
                    Storage s = r.storages.get(p[1]);
                    long n = Long.parseLong(p[4]);
                    s.items.put(new ItemKey(p[2], Integer.parseInt(p[3])), n);
                    s.used += n;
                }
                case "P" -> r.storages.get(p[1]).pending.put(new ItemKey(p[2], Integer.parseInt(p[3])), Long.parseLong(p[4]));
                default -> { }
            }
        }
        return r;
    }
}
