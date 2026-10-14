package com.deepstoragesync.core;

/** Identifies an item type. variant == 0 means "no extra data"; other values stand for distinct NBT/component sets. */
public record ItemKey(String id, int variant) implements Comparable<ItemKey> {

    public static ItemKey of(String id) {
        return new ItemKey(id, 0);
    }

    /** Same item with the variant stripped, used for NBT-agnostic UI grouping. */
    public ItemKey agnostic() {
        return variant == 0 ? this : new ItemKey(id, 0);
    }

    /** Item id without the namespace, e.g. "minecraft:oak_planks" -> "oak_planks". */
    public String path() {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    @Override
    public int compareTo(ItemKey o) {
        int c = id.compareTo(o.id);
        return c != 0 ? c : Integer.compare(variant, o.variant);
    }
}
