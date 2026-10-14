package com.deepstoragesync;

import com.deepstoragesync.game.ModItems;
import com.deepstoragesync.game.StorageMenu;
import com.deepstoragesync.game.StorageNet;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeepStorageSync implements ModInitializer {
    public static final String MOD_ID = "deep_storage_sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModItems.initialize();

        ServerLifecycleEvents.SERVER_STARTED.register(StorageNet::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(StorageNet::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(StorageNet::onTick);

        // Storage Linker: right-click = link/unlink, sneak + right-click = set as inbox.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!held.is(ModItems.LINKER)) return InteractionResult.PASS;
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            String msg = StorageNet.toggleLink((ServerLevel) level, hit.getBlockPos(), player.isShiftKeyDown());
            player.displayClientMessage(Component.literal(msg), true);
            return InteractionResult.SUCCESS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("dss")
                        .executes(ctx -> {
                            StorageMenu.open(ctx.getSource().getPlayerOrException(), "");
                            return 1;
                        })
                        .then(Commands.literal("search")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            StorageMenu.open(ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "text"));
                                            return 1;
                                        })))
                        .then(Commands.literal("dump")
                                .executes(ctx -> {
                                    String msg = StorageNet.dump(ctx.getSource().getPlayerOrException());
                                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                    return 1;
                                }))
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    String msg = StorageNet.status();
                                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                    return 1;
                                }))
                        .then(Commands.literal("linker")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    ItemStack stack = new ItemStack(ModItems.LINKER);
                                    if (!p.getInventory().add(stack)) p.drop(stack, false);
                                    return 1;
                                }))));

        LOGGER.info("Deep Storage Sync loaded");
    }
}
