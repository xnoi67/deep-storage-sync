package com.deepstoragesync.game;

import com.deepstoragesync.DeepStorageSync;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {

    /** Right-click a chest/barrel/shulker box to link it; sneak + right-click to make it the inbox. */
    public static final Item LINKER = register("linker", new Item.Properties().stacksTo(1));

    private ModItems() { }

    private static Item register(String name, Item.Properties properties) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(DeepStorageSync.MOD_ID, name));
        Item item = new Item(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    /** Touching this class registers the items; call from the mod initializer. */
    public static void initialize() { }
}
