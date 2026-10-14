package com.deepstoragesync.game;

import com.deepstoragesync.core.ContextSorter;
import com.deepstoragesync.core.SearchFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server-side 6-row chest window: works with a vanilla client. The top 45 slots list
 * network items (left click = a stack, right click = one), the bottom row holds buttons.
 */
public final class StorageMenu extends ChestMenu {

    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;

    private final SimpleContainer display;
    private final ServerPlayer owner;
    private final String filter;
    private boolean smart = true;
    private int page = 0;
    private List<ContextSorter.Entry> view = List.of();

    private StorageMenu(int id, Inventory inventory, SimpleContainer display, ServerPlayer owner, String filter) {
        super(MenuType.GENERIC_9x6, id, inventory, display, 6);
        this.display = display;
        this.owner = owner;
        this.filter = filter;
        render();
    }

    public static void open(ServerPlayer player, String filter) {
        StorageNet.flush();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new StorageMenu(id, inventory, new SimpleContainer(SIZE), player, filter),
                Component.literal("Deep Storage Sync")));
    }

    private static ItemStack named(Item item, int count, Component name) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, name);
        return stack;
    }

    private void render() {
        List<ContextSorter.Entry> all = new ArrayList<>();
        StorageNet.registry().totals().forEach((key, total) -> {
            if (key.variant() == 0 && total > 0 && StorageNet.itemOf(key.id()) != null) {
                all.add(new ContextSorter.Entry(key, total));
            }
        });
        List<ContextSorter.Entry> filtered = SearchFilter.filter(all, filter);
        if (smart) {
            view = StorageNet.SORTER.sort(filtered);
        } else {
            List<ContextSorter.Entry> byName = new ArrayList<>(filtered);
            byName.sort(Comparator.comparing((ContextSorter.Entry e) -> e.key().path()));
            view = byName;
        }

        int pages = Math.max(1, (view.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= view.size()) {
                display.setItem(i, ItemStack.EMPTY);
                continue;
            }
            ContextSorter.Entry entry = view.get(index);
            Item item = StorageNet.itemOf(entry.key().id());
            int shown = (int) Math.min(entry.count(), new ItemStack(item).getMaxStackSize());
            Component name = Component.literal("")
                    .append(new ItemStack(item).getHoverName())
                    .append(Component.literal("  x" + String.format("%,d", entry.count())));
            display.setItem(i, named(item, Math.max(1, shown), name));
        }

        ItemStack pane = named(Items.GRAY_STAINED_GLASS_PANE, 1, Component.literal(" "));
        for (int i = PAGE_SIZE; i < SIZE; i++) display.setItem(i, pane.copy());
        display.setItem(45, named(Items.ARROW, 1, Component.literal("<< Previous page")));
        display.setItem(46, named(Items.ARROW, 1, Component.literal("Next page >>")));
        display.setItem(47, named(Items.NAME_TAG, 1, Component.literal(
                smart ? "Sort: Smart (click for A-Z)" : "Sort: A-Z (click for Smart)")));
        display.setItem(49, named(Items.HOPPER, 1, Component.literal("Dump your inventory into the network")));
        String info = "Page " + (page + 1) + "/" + pages + " - " + view.size() + " item types - "
                + StorageNet.registry().storageIds().size() + " storages"
                + (filter.isEmpty() ? "" : " - search: " + filter);
        display.setItem(51, named(Items.PAPER, 1, Component.literal(info)));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Double-click "gather all" could pull the display items, so it is blocked entirely.
        if (clickType == ClickType.PICKUP_ALL) return;
        if (slotId < 0 || slotId >= SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (clickType != ClickType.PICKUP && clickType != ClickType.QUICK_MOVE) return;

        if (slotId < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slotId;
            if (index < view.size()) {
                ContextSorter.Entry entry = view.get(index);
                Item item = StorageNet.itemOf(entry.key().id());
                if (item != null) {
                    int amount = button == 1 ? 1 : new ItemStack(item).getMaxStackSize();
                    StorageNet.pull(owner, entry.key(), amount);
                }
            }
        } else {
            switch (slotId) {
                case 45 -> page--;
                case 46 -> page++;
                case 47 -> smart = !smart;
                case 49 -> owner.displayClientMessage(Component.literal(StorageNet.dump(owner)), true);
                default -> { }
            }
        }
        render();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
