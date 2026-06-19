package main.java.possumswelcomeitems;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PossumsWelcomeItems extends JavaPlugin implements Listener, TabExecutor {

    // Main plugin toggle
    private boolean enabled;

    // Chat prefix
    private static final String PREFIX = "&a🐀 &2&lWelcome items &8»";

    // Main command name (plugin.yml)
    private static final String COMMAND_NAME = "welcomeitems";

    // One welcome slot for each hotbar slot
    private static final int FIRST_WELCOME_SLOT = 1;
    private static final int LAST_WELCOME_SLOT = 9;

    // This stores the exact ItemStack saved from a player's hand.
    // It preserves custom plugin data much better than rebuilding only
    // material/lore/book data.
    private static final String EXACT_ITEM_KEY = ".exact-item";

    // Supports config text like <#4A90E2>A Guide To Moonshire</#98AFD6>.
    private static final Pattern HEX_GRADIENT_PATTERN = Pattern.compile(
            "(?i)<#([0-9a-f]{6})>(.*?)</#([0-9a-f]{6})>",
            Pattern.DOTALL);

    // Also supports solid hex text like <#4A90E2> and &#4A90E2.
    private static final Pattern HEX_TAG_PATTERN = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("(?i)&\\#([0-9a-f]{6})");

    // Command options, for tab completion
    private static final List<String> COMMAND_OPTIONS = List.of(
            "enable",
            "disable",
            "status",
            "reload",
            "list",
            "get",
            "set",
            "remove");

    // Usage text
    private static final List<String> USAGE_LINES = List.of(
            "enable",
            "disable",
            "status",
            "reload",
            "list",
            "get",
            "set <1-9>",
            "remove <1-9>");

    // Slots that the user can input
    private static final List<String> SLOT_OPTIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9");

    private final Map<Integer, ItemStack> welcomeItems = new HashMap<>();

    @Override
    public void onEnable() {
        // Create config if needed, load current settings.
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        registerCommand();

        getLogger().info(
                "PossumsWelcomeItems enabled. Welcome items: " + ChatColor.stripColor(colorize(statusText(enabled))));
    }

    private void registerCommand() {
        if (getCommand(COMMAND_NAME) == null)
            return;

        getCommand(COMMAND_NAME).setExecutor(this);
        getCommand(COMMAND_NAME).setTabCompleter(this);
    }

    private void loadSettings() {
        enabled = getConfig().getBoolean("enabled", true);
        welcomeItems.clear();

        for (int slot = FIRST_WELCOME_SLOT; slot <= LAST_WELCOME_SLOT; slot++) {
            ItemStack item = readConfiguredItem(slot);

            if (!isEmptyItem(item)) {
                welcomeItems.put(slot, item);
            }
        }
    }

    private void saveEnabledSetting() {
        getConfig().set("enabled", enabled);
        saveConfig();
    }

    // Give a player their welcome items when they join for the first time.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled)
            return;

        Player player = event.getPlayer();

        if (player.hasPlayedBefore())
            return;

        giveWelcomeItems(player, false);
    }

    // Actual item giving logic.
    private void giveWelcomeItems(Player player, boolean manualCommand) {
        if (welcomeItems.isEmpty()) {
            sendManualOnlyMessage(player, manualCommand, "&cNo welcome items are configured.");
            return;
        }

        if (!hasEnoughOpenInventorySlots(player, welcomeItems.size())) {
            player.sendMessage(colorize(PREFIX + " &cYour inventory is too full!"));
            return;
        }

        for (int slot = FIRST_WELCOME_SLOT; slot <= LAST_WELCOME_SLOT; slot++) {
            ItemStack item = welcomeItems.get(slot);

            if (!isEmptyItem(item)) {
                placeWelcomeItem(player, slot, item.clone());
            }
        }

        player.updateInventory();
        sendManualOnlyMessage(player, manualCommand, "&aWelcome items given.");
    }

    // Only show feedback if an actual command is entered.
    private void sendManualOnlyMessage(Player player, boolean manualCommand, String message) {
        if (manualCommand) {
            player.sendMessage(colorize(PREFIX + " " + message));
        }
    }

    // Add a single item to a player inventory.
    private void placeWelcomeItem(Player player, int welcomeSlot, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        int preferredInventoryIndex = welcomeSlot - 1;

        if (isEmptyItem(inventory.getItem(preferredInventoryIndex))) {
            inventory.setItem(preferredInventoryIndex, item);
            return;
        }

        inventory.addItem(item);
    }

    // First check whether an inventory has space.
    private boolean hasEnoughOpenInventorySlots(Player player, int neededSlots) {
        int openSlots = 0;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isEmptyItem(item)) {
                openSlots++;

                if (openSlots >= neededSlots) {
                    return true;
                }
            }
        }

        return false;
    }

    // Read a single item from config.yml.
    private ItemStack readConfiguredItem(int slot) {
        String path = slotPath(slot);

        ItemStack exactItem = readExactItem(path);
        if (!isEmptyItem(exactItem)) {
            return exactItem;
        }

        Material material = readConfiguredMaterial(path, slot);

        if (material == null)
            return null;

        int amount = clampAmount(getConfig().getInt(path + ".amount", 1), material);
        ItemStack item = new ItemStack(material, amount);

        applyItemMeta(item, path);
        return item;
    }

    // Read a saved ItemStack snapshot from config.yml.
    private ItemStack readExactItem(String path) {
        ItemStack exactItem = getConfig().getItemStack(path + EXACT_ITEM_KEY);

        if (isEmptyItem(exactItem)) {
            return null;
        }

        return exactItem.clone();
    }

    // Read a respective material for an item slot.
    private Material readConfiguredMaterial(String path, int slot) {
        String rawItem = getConfig().getString(path + ".item", "");

        if (isBlank(rawItem) || rawItem.equalsIgnoreCase("unset")) {
            return null;
        }

        Material material = Material.matchMaterial(rawItem.trim());

        if (material == null || material == Material.AIR) {
            getLogger().warning("Ignoring welcome items slot " + slot + " because item '" + rawItem + "' is invalid.");
            return null;
        }

        return material;
    }

    // Apply optional book data from config.yml.
    private void applyItemMeta(ItemStack item, String path) {
        ItemMeta meta = item.getItemMeta();

        if (meta == null)
            return;

        applyDisplayNameMeta(meta, path);
        applyHoverTextMeta(meta, path);
        applyBookMeta(meta, path);

        item.setItemMeta(meta);
    }

    // Add optional item display name from config.
    private void applyDisplayNameMeta(ItemMeta meta, String path) {
        String name = getConfig().getString(path + ".name", "");

        if (!isBlank(name)) {
            meta.setDisplayName(colorize(name));
        }
    }

    // Add optional hover-text from config.
    private void applyHoverTextMeta(ItemMeta meta, String path) {
        List<String> hoverText = getConfig().getStringList(path + ".hoverText");

        if (!hoverText.isEmpty()) {
            meta.setLore(colorizedLines(hoverText));
        }
    }

    // Author, title, pages.
    private void applyBookMeta(ItemMeta meta, String path) {
        if (!(meta instanceof BookMeta bookMeta))
            return;

        String title = getConfig().getString(path + ".book.title", "");
        String author = getConfig().getString(path + ".book.author", "");
        List<String> pages = getConfig().getStringList(path + ".book.pages");

        if (!isBlank(title)) {
            bookMeta.setTitle(colorize(title));
        }

        if (!isBlank(author)) {
            bookMeta.setAuthor(colorize(author));
        }

        if (!pages.isEmpty()) {
            bookMeta.setPages(colorizedLines(pages));
        }
    }

    // Apply minecraft color codes.
    private List<String> colorizedLines(List<String> lines) {
        List<String> coloredLines = new ArrayList<>();

        for (String line : lines) {
            coloredLines.add(colorize(line));
        }

        return coloredLines;
    }

    // Save the held item to a slot as an exact ItemStack snapshot.
    private void saveItemToConfig(int slot, ItemStack item) {
        String path = slotPath(slot);

        getConfig().set(path + ".item", item.getType().name());
        getConfig().set(path + ".amount", item.getAmount());
        getConfig().set(path + EXACT_ITEM_KEY, item.clone());

        // These older readable sections are only used by hand-written config slots.
        // Clear them when a command saves an exact item so they cannot override custom
        // data.
        getConfig().set(path + ".name", null);
        getConfig().set(path + ".hoverText", List.of());
        getConfig().set(path + ".book", null);
        getConfig().set(path + ".imageframe", null);

        saveAndReloadSettings();
    }

    // Remove an item from the loadout.
    private void removeItemFromConfig(int slot) {
        String path = slotPath(slot);

        getConfig().set(path + ".item", "");
        getConfig().set(path + ".amount", 1);
        getConfig().set(path + EXACT_ITEM_KEY, null);
        getConfig().set(path + ".name", null);
        getConfig().set(path + ".hoverText", List.of());
        getConfig().set(path + ".book", null);
        getConfig().set(path + ".imageframe", null);

        saveAndReloadSettings();
    }

    private void saveAndReloadSettings() {
        saveConfig();
        reloadConfig();
        loadSettings();
    }

    private String slotPath(int slot) {
        return "slots." + slot;
    }

    // Restrain stack amounts if needed.
    private int clampAmount(int amount, Material material) {
        if (amount < 1) {
            return 1;
        }

        return Math.min(amount, material.getMaxStackSize());
    }

    private int parseSlot(String rawSlot) {
        try {
            int slot = Integer.parseInt(rawSlot);

            if (slot >= FIRST_WELCOME_SLOT && slot <= LAST_WELCOME_SLOT) {
                return slot;
            }
        } catch (NumberFormatException ignored) {
        }

        return -1;
    }

    // Show all configurations to the user.
    private void sendList(CommandSender sender) {
        sender.sendMessage(colorize(PREFIX + " &fCurrent welcome items are:"));

        for (int slot = FIRST_WELCOME_SLOT; slot <= LAST_WELCOME_SLOT; slot++) {
            ItemStack item = welcomeItems.get(slot);

            if (isEmptyItem(item)) {
                sender.sendMessage(colorize("&7" + slot + " - unset"));
                continue;
            }

            sender.sendMessage(colorize("&7" + slot + " - &e" + readableItemName(item)));
        }
    }

    private String readableItemName(ItemStack item) {
        if (isEmptyItem(item)) {
            return "unset";
        }

        ItemMeta meta = item.getItemMeta();

        if (meta != null && meta.hasDisplayName()) {
            return prettyMaterialName(item.getType()) + " - " + ChatColor.stripColor(meta.getDisplayName());
        }

        if (meta instanceof BookMeta bookMeta && bookMeta.hasTitle()) {
            return prettyMaterialName(item.getType()) + " - " + ChatColor.stripColor(bookMeta.getTitle());
        }

        return prettyMaterialName(item.getType());
    }

    private String prettyMaterialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace("_", " ");
    }

    private boolean isEmptyItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String statusText(boolean value) {
        return value ? "&aENABLED" : "&cDISABLED";
    }

    private String colorize(String message) {
        if (message == null)
            return "";

        String withGradients = applyHexGradients(message);
        String withSolidHex = applySolidHexColors(withGradients);
        return ChatColor.translateAlternateColorCodes('&', withSolidHex);
    }

    private String applyHexGradients(String message) {
        Matcher matcher = HEX_GRADIENT_PATTERN.matcher(message);
        StringBuffer output = new StringBuffer();

        while (matcher.find()) {
            String replacement = createGradientText(
                    matcher.group(2),
                    matcher.group(1),
                    matcher.group(3));

            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(output);
        return output.toString();
    }

    private String applySolidHexColors(String message) {
        String tagConverted = replaceHexPattern(message, HEX_TAG_PATTERN);
        return replaceHexPattern(tagConverted, AMP_HEX_PATTERN);
    }

    private String replaceHexPattern(String message, Pattern pattern) {
        Matcher matcher = pattern.matcher(message);
        StringBuffer output = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(toLegacyHexColor(matcher.group(1))));
        }

        matcher.appendTail(output);
        return output.toString();
    }

    private String createGradientText(String text, String startHex, String endHex) {
        int visibleCharacters = countVisibleCodePoints(text);

        if (visibleCharacters <= 0) {
            return text;
        }

        int[] start = parseHexColor(startHex);
        int[] end = parseHexColor(endHex);
        StringBuilder output = new StringBuilder();
        int visibleIndex = 0;

        for (int index = 0; index < text.length();) {
            char current = text.charAt(index);

            if (isLegacyColorCode(text, index)) {
                output.append(current).append(text.charAt(index + 1));
                index += 2;
                continue;
            }

            int codePoint = text.codePointAt(index);
            double ratio = visibleCharacters == 1 ? 0.0D : (double) visibleIndex / (visibleCharacters - 1);
            String hex = interpolateHex(start, end, ratio);

            output.append(toLegacyHexColor(hex));
            output.appendCodePoint(codePoint);

            visibleIndex++;
            index += Character.charCount(codePoint);
        }

        return output.toString();
    }

    private int countVisibleCodePoints(String text) {
        int count = 0;

        for (int index = 0; index < text.length();) {
            if (isLegacyColorCode(text, index)) {
                index += 2;
                continue;
            }

            int codePoint = text.codePointAt(index);
            count++;
            index += Character.charCount(codePoint);
        }

        return count;
    }

    private boolean isLegacyColorCode(String text, int index) {
        if (index + 1 >= text.length()) {
            return false;
        }

        char marker = text.charAt(index);
        char code = Character.toLowerCase(text.charAt(index + 1));

        return (marker == '&' || marker == ChatColor.COLOR_CHAR)
                && "0123456789abcdefklmnorx".indexOf(code) >= 0;
    }

    private int[] parseHexColor(String hex) {
        return new int[] {
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    private String interpolateHex(int[] start, int[] end, double ratio) {
        int red = interpolateColorChannel(start[0], end[0], ratio);
        int green = interpolateColorChannel(start[1], end[1], ratio);
        int blue = interpolateColorChannel(start[2], end[2], ratio);

        return String.format("%02X%02X%02X", red, green, blue);
    }

    private int interpolateColorChannel(int start, int end, double ratio) {
        return (int) Math.round(start + ((end - start) * ratio));
    }

    private String toLegacyHexColor(String hex) {
        String cleanHex = hex.toUpperCase(Locale.ROOT);
        StringBuilder output = new StringBuilder();

        output.append(ChatColor.COLOR_CHAR).append('x');

        for (char digit : cleanHex.toCharArray()) {
            output.append(ChatColor.COLOR_CHAR).append(digit);
        }

        return output.toString();
    }

    // Main command handler.
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("possumswelcomeitems.admin")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "enable" -> {
                setPluginEnabled(sender, true);
                return true;
            }

            case "disable" -> {
                setPluginEnabled(sender, false);
                return true;
            }

            case "status" -> {
                sendStatus(sender);
                return true;
            }

            case "reload" -> {
                reloadConfig();
                loadSettings();
                sender.sendMessage(colorize(PREFIX + " &aConfig reloaded."));
                return true;
            }

            case "list" -> {
                sendList(sender);
                return true;
            }

            case "get" -> {
                handleGetCommand(sender);
                return true;
            }

            case "set" -> {
                handleSetCommand(sender, label, args);
                return true;
            }

            case "remove" -> {
                handleRemoveCommand(sender, label, args);
                return true;
            }

            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }

    // Toggle the plugin, save immediately.
    private void setPluginEnabled(CommandSender sender, boolean newValue) {
        enabled = newValue;
        saveEnabledSetting();

        String message = newValue ? "&aWelcome items enabled." : "&cWelcome items disabled.";
        sender.sendMessage(colorize(PREFIX + " " + message));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(colorize(PREFIX + " &fWelcome items: " + statusText(enabled)));
        sender.sendMessage(colorize(PREFIX + " &fConfigured slots: &e" + welcomeItems.size() + "&f/&e9"));
    }

    // Manually give the welcome items to the player.
    private void handleGetCommand(CommandSender sender) {
        if (!enabled) {
            sender.sendMessage(colorize(PREFIX + " &cWelcome items are disabled."));
            return;
        }

        Player player = requirePlayer(sender);

        if (player == null)
            return;

        giveWelcomeItems(player, true);
    }

    // Save the item in the player's hand to the loadout.
    private void handleSetCommand(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);

        if (player == null)
            return;

        if (args.length != 2) {
            sender.sendMessage(colorize(PREFIX + " &cUsage: /" + label + " set <1-9>"));
            return;
        }

        int slot = requireValidSlot(sender, args[1]);

        if (slot == -1)
            return;

        ItemStack heldItem = player.getInventory().getItemInMainHand();

        if (isEmptyItem(heldItem)) {
            sender.sendMessage(colorize(PREFIX + " &cHold the item you want to save first."));
            return;
        }

        saveItemToConfig(slot, heldItem.clone());
        sender.sendMessage(
                colorize(PREFIX + " &aSlot &e" + slot + " &aset to &e" + readableItemName(heldItem) + "&a."));
    }

    // Remove a saved welcome item manually.
    private void handleRemoveCommand(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(colorize(PREFIX + " &cUsage: /" + label + " remove <1-9>"));
            return;
        }

        int slot = requireValidSlot(sender, args[1]);

        if (slot == -1)
            return;

        removeItemFromConfig(slot);
        sender.sendMessage(colorize(PREFIX + " &aSlot &e" + slot + " &awas removed."));
    }

    // Make commands like get and set player only.
    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }

        sender.sendMessage("Only a player can use this command.");
        return null;
    }

    private int requireValidSlot(CommandSender sender, String rawSlot) {
        int slot = parseSlot(rawSlot);

        if (slot == -1) {
            sender.sendMessage(colorize(PREFIX + " &cSlot must be a number from 1 to 9."));
        }

        return slot;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(colorize(PREFIX + " &cUsage:"));

        for (String usageLine : USAGE_LINES) {
            sender.sendMessage(colorize("/" + label + " " + usageLine));
        }
    }

    // Tab Completion.
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("possumswelcomeitems.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return filterStartingWith(args[0], COMMAND_OPTIONS);
        }

        if (args.length == 2 && isSlotCommand(args[0])) {
            return filterStartingWith(args[1], SLOT_OPTIONS);
        }

        return new ArrayList<>();
    }

    private boolean isSlotCommand(String commandName) {
        return commandName.equalsIgnoreCase("set") || commandName.equalsIgnoreCase("remove");
    }

    private List<String> filterStartingWith(String input, List<String> options) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowerInput)) {
                matches.add(option);
            }
        }

        return matches;
    }
}
