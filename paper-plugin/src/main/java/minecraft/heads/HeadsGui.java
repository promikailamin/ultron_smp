package minecraft.heads;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Paginated inventory showing every mob head known to the plugin. */
public final class HeadsGui implements InventoryHolder, Listener {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final HeadsPlugin plugin;
    private final int page;
    private final List<EntityType> entries;
    private Inventory inventory;

    public HeadsGui(HeadsPlugin plugin, int page) {
        this.plugin = plugin;
        List<EntityType> all = plugin.textures().types();
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        int from = this.page * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        this.entries = new ArrayList<>(all.subList(from, to));
    }

    public static int pageCount(HeadsPlugin plugin) {
        return Math.max(1, (plugin.textures().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public void open(Player player) {
        int pages = pageCount(plugin);
        Component title = Component.text("Mob Heads (" + (page + 1) + "/" + pages + ")");
        inventory = Bukkit.createInventory(this, 54, title);
        for (int i = 0; i < entries.size(); i++) {
            inventory.setItem(i, HeadFactory.create(plugin.textures(), entries.get(i)));
        }
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " ");
        for (int slot = PAGE_SIZE; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, named(new ItemStack(Material.ARROW), "Previous page"));
        }
        if (page + 1 < pages) {
            inventory.setItem(NEXT_SLOT, named(new ItemStack(Material.ARROW), "Next page"));
        }
        inventory.setItem(CLOSE_SLOT, named(new ItemStack(Material.BARRIER), "Close"));
        player.openInventory(inventory);
    }

    private static ItemStack named(ItemStack item, String name) {
        item.editMeta(meta -> meta.displayName(Component.text(name)
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HeadsGui gui)) {
            return;
        }
        if (gui.plugin != plugin) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == PREVIOUS_SLOT && gui.page > 0) {
            new HeadsGui(plugin, gui.page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && gui.page + 1 < pageCount(plugin)) {
            new HeadsGui(plugin, gui.page + 1).open(player);
            return;
        }
        if (slot >= gui.entries.size()) {
            return;
        }
        if (!player.hasPermission("heads.give")) {
            player.sendMessage(Component.text("You do not have permission to take heads.",
                    NamedTextColor.RED));
            return;
        }
        EntityType type = gui.entries.get(slot);
        ItemStack head = HeadFactory.create(plugin.textures(), type);
        if (head == null) {
            return;
        }
        player.getInventory().addItem(head).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(Component.text("You received a " + HeadTextures.displayName(type)
                + " Head.", NamedTextColor.GREEN));
    }
}
