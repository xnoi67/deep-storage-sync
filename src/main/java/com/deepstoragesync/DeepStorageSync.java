package com.deepstoragesync;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeepStorageSync implements ModInitializer {
    public static final String MOD_ID = "deep_storage_sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // The Minecraft-facing layer (blocks, items, packets, UI) plugs into the core package from here.
        LOGGER.info("Deep Storage Sync loaded");
    }
}
