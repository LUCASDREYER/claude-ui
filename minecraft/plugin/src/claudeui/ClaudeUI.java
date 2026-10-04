package claudeui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Helper for the claude-ui Minecraft bridge, which drives it from the server console:
 *   claudeui open <player> <base64 json>     open an item menu
 *   claudeui update <player> <base64 json>   refresh that menu, only if the player has it open
 *   claudeui close <player> | closedialog <player>
 * and reads its replies from the console log:
 *   click <player> <menu> <item>             an item in a menu was clicked
 *   dialog <player> <id> <base64 json>       a claude:* dialog button, with the dialog's inputs
 */
public final class ClaudeUI extends JavaPlugin implements Listener {
  private static final Gson GSON = new Gson();
  private static final String[] INPUT_KEYS = {"prompt", "mode", "q0", "q1", "q2", "q3"};

  /** An open item menu; remembers which item id sits in which slot. */
  private static final class Menu implements InventoryHolder {
    final String name;
    final Map<Integer, String> ids = new HashMap<>();
    final Set<Integer> closing = new HashSet<>();
    Inventory inventory;

    Menu(String name) {
      this.name = name;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  @Override
  public void onEnable() {
    getServer().getPluginManager().registerEvents(this, this);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof ConsoleCommandSender) || args.length < 2) return true;
    Player player = Bukkit.getPlayerExact(args[1]);
    if (player == null) return true;
    switch (args[0]) {
      case "open" -> {
        if (args.length > 2) show(player, decode(args[2]), false);
      }
      case "update" -> {
        if (args.length > 2) show(player, decode(args[2]), true);
      }
      case "close" -> {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
      }
      case "closedialog" -> player.closeDialog();
      default -> {}
    }
    return true;
  }

  private static JsonObject decode(String base64) {
    return GSON.fromJson(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8), JsonObject.class);
  }

  /** Fills the menu in place when the player is already looking at it, so the cursor doesn't jump. */
  private void show(Player player, JsonObject spec, boolean onlyIfOpen) {
    String name = spec.get("name").getAsString();
    int size = spec.get("rows").getAsInt() * 9;
    Inventory top = player.getOpenInventory().getTopInventory();
    Menu menu;
    if (top.getHolder() instanceof Menu current && current.name.equals(name) && top.getSize() == size) {
      menu = current;
      menu.ids.clear();
      menu.closing.clear();
      top.clear();
    } else {
      if (onlyIfOpen) return;
      menu = new Menu(name);
      menu.inventory = Bukkit.createInventory(menu, size, text(spec.get("title"), null));
    }
    for (JsonElement element : spec.getAsJsonArray("items")) {
      JsonObject item = element.getAsJsonObject();
      int slot = item.get("slot").getAsInt();
      Material material = Material.matchMaterial(item.get("material").getAsString());
      if (material == null || slot < 0 || slot >= size) continue;
      ItemStack stack = new ItemStack(material);
      ItemMeta meta = stack.getItemMeta();
      meta.displayName(text(item.get("name"), item.has("color") ? item.get("color").getAsString() : "white"));
      if (item.has("lore")) {
        List<Component> lore = new ArrayList<>();
        for (JsonElement line : item.getAsJsonArray("lore")) lore.add(text(line, "gray"));
        meta.lore(lore);
      }
      if (item.has("glow") && item.get("glow").getAsBoolean()) meta.setEnchantmentGlintOverride(true);
      stack.setItemMeta(meta);
      menu.inventory.setItem(slot, stack);
      if (item.has("id")) menu.ids.put(slot, item.get("id").getAsString());
      if (item.has("close") && item.get("close").getAsBoolean()) menu.closing.add(slot);
    }
    if (top.getHolder() != menu) player.openInventory(menu.inventory);
  }

  private static Component text(JsonElement value, String color) {
    Component component = Component.text(value == null ? "" : value.getAsString()).decoration(TextDecoration.ITALIC, false);
    if (color == null) return component;
    TextColor parsed = color.startsWith("#") ? TextColor.fromHexString(color) : NamedTextColor.NAMES.value(color);
    return parsed == null ? component : component.color(parsed);
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) return;
    String id = menu.ids.get(event.getRawSlot());
    if (id == null) return;
    Player player = (Player) event.getWhoClicked();
    getLogger().info("click " + player.getName() + " " + menu.name + " " + id);
    if (menu.closing.contains(event.getRawSlot())) Bukkit.getScheduler().runTask(this, () -> player.closeInventory());
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }

  @EventHandler
  public void onCustomClick(PlayerCustomClickEvent event) {
    if (!event.getIdentifier().namespace().equals("claude")) return;
    if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
    JsonObject inputs = new JsonObject();
    DialogResponseView view = event.getDialogResponseView();
    if (view != null) {
      for (String key : INPUT_KEYS) {
        String value = view.getText(key);
        if (value != null) inputs.addProperty(key, value);
      }
    }
    String payload = Base64.getEncoder().encodeToString(GSON.toJson(inputs).getBytes(StandardCharsets.UTF_8));
    getLogger().info("dialog " + connection.getPlayer().getName() + " " + event.getIdentifier().value() + " " + payload);
  }
}
