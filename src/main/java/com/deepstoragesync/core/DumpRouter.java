package com.deepstoragesync.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** One-click dump: top up storages that already hold the item, send unknown items to the overflow chest. */
public final class DumpRouter {

    public record Result(Map<String, Map<ItemKey, Long>> placements, Map<ItemKey, Long> rejected) { }

    private DumpRouter() { }

    public static Result route(GlobalRegistry registry, Map<ItemKey, Long> items, String overflowStorageId) {
        Map<String, Map<ItemKey, Long>> placements = new TreeMap<>();
        Map<ItemKey, Long> rejected = new HashMap<>();

        for (Map.Entry<ItemKey, Long> e : items.entrySet()) {
            ItemKey key = e.getKey();
            long left = e.getValue();

            for (String id : registry.holders(key)) {
                if (left <= 0) break;
                left -= place(registry, placements, id, key, left);
            }
            if (left > 0 && overflowStorageId != null) {
                left -= place(registry, placements, overflowStorageId, key, left);
            }
            if (left > 0) rejected.put(key, left);
        }
        return new Result(placements, rejected);
    }

    private static long place(GlobalRegistry registry, Map<String, Map<ItemKey, Long>> placements,
                              String id, ItemKey key, long amount) {
        long n = registry.deposit(id, key, amount);
        if (n > 0) placements.computeIfAbsent(id, k -> new HashMap<>()).merge(key, n, Long::sum);
        return n;
    }
}
