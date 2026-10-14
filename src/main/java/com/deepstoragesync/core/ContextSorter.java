package com.deepstoragesync.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sorts by what the player is doing, not alphabetically: hot items first, then work-groups, bulk junk last. */
public final class ContextSorter {

    public enum Category { BUILDER, REDSTONE, OTHER, TRASH }

    public record Entry(ItemKey key, long count) { }

    public record Group(ItemKey key, long total, int variants) { }

    private static final Set<String> TRASH_IDS = Set.of(
            "dirt", "cobblestone", "gravel", "stone", "andesite", "diorite", "granite",
            "deepslate", "cobbled_deepslate", "netherrack", "sand", "tuff");

    private static final String[] REDSTONE_WORDS = {
            "redstone", "dispenser", "dropper", "piston", "repeater", "comparator", "observer",
            "hopper", "command_block", "lever", "button", "pressure_plate", "rail", "tripwire", "target"};

    private static final String[] BUILDER_WORDS = {
            "concrete", "planks", "stairs", "fence", "slab", "log", "wood", "wall", "glass",
            "bricks", "terracotta", "door", "trapdoor"};

    private final Map<ItemKey, Double> heat = new HashMap<>();
    private final long bulkThreshold;
    private final double hotThreshold;

    /** @param bulkThreshold stack count above which common junk counts as trash (default 1728 = 27 stacks) */
    public ContextSorter(long bulkThreshold, double hotThreshold) {
        this.bulkThreshold = bulkThreshold;
        this.hotThreshold = hotThreshold;
    }

    public ContextSorter() {
        this(1728, 3.0);
    }

    /** Player Memory: call whenever the player pulls this item from the network. */
    public synchronized void recordPull(ItemKey key) {
        heat.merge(key.agnostic(), 1.0, Double::sum);
    }

    /** Call periodically (e.g. hourly) so old habits fade. */
    public synchronized void decay(double factor) {
        heat.replaceAll((k, v) -> v * factor);
        heat.values().removeIf(v -> v < 0.05);
    }

    public static Category categorize(ItemKey key, long count, long bulkThreshold) {
        String p = key.path();
        if (TRASH_IDS.contains(p) && count >= bulkThreshold) return Category.TRASH;
        for (String w : REDSTONE_WORDS) if (p.contains(w)) return Category.REDSTONE;
        for (String w : BUILDER_WORDS) if (p.contains(w)) return Category.BUILDER;
        return Category.OTHER;
    }

    public synchronized List<Entry> sort(Collection<Entry> input) {
        Map<Entry, Integer> rank = new HashMap<>();
        Map<Entry, Double> score = new HashMap<>();
        for (Entry e : input) {
            double h = heat.getOrDefault(e.key().agnostic(), 0.0);
            score.put(e, h);
            rank.put(e, h >= hotThreshold ? 0 : 1 + categorize(e.key(), e.count(), bulkThreshold).ordinal());
        }
        List<Entry> out = new ArrayList<>(input);
        out.sort(Comparator
                .comparingInt((Entry e) -> rank.get(e))
                .thenComparing((Entry e) -> -score.get(e))
                .thenComparing(Entry::key));
        return out;
    }

    /** NBT-agnostic UI option: merge variants of the same item into one icon with a summed count. */
    public static List<Group> collapse(Collection<Entry> entries) {
        Map<ItemKey, long[]> acc = new LinkedHashMap<>();
        for (Entry e : entries) {
            long[] a = acc.computeIfAbsent(e.key().agnostic(), k -> new long[2]);
            a[0] += e.count();
            a[1]++;
        }
        List<Group> out = new ArrayList<>();
        acc.forEach((k, a) -> out.add(new Group(k, a[0], (int) a[1])));
        return out;
    }
}
