package com.gearflash;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.WidgetUtil;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Gear Flash
 *
 * SHIFT + RIGHT-CLICK inventory item:
 *
 *   Set trigger group >
 *       Melee
 *       Range
 *       Mage
 *
 *   Set trigger >
 *       Melee
 *       Range
 *       Mage
 *
 * Gear items are what flash.
 * Trigger weapons only activate the group; they do NOT flash unless you also
 * deliberately assign them to a gear group.
 *
 * Example:
 *   Fang -> Melee trigger
 *   DWH  -> Melee trigger
 *
 *   Bandos chest -> Melee gear
 *   Bandos tassets -> Melee gear
 *   Primordial boots -> Melee gear
 *
 * Equip Fang or DWH -> melee gear flashes.
 *
 * Visual only. No clicks/equips/input automation.
 */
@Slf4j
@PluginDescriptor(
    name = "Gear Flash",
    description = "Shift-right-click trigger weapons and trigger groups",
    tags = {"gear", "switch", "inventory", "highlight", "melee", "range", "mage"},
    enabledByDefault = false
)
public class GearFlashPlugin extends Plugin
{
    private static final String STORAGE_GROUP = "gearflash_groups_triggers";

    private static final int SAVE_FILE_VERSION = 1;
    private static final Gson GSON =
        new GsonBuilder().setPrettyPrinting().create();

    /*
     * Human-readable backup/export file.
     *
     * RuneLite profile configuration remains the primary live storage.
     * This JSON file mirrors assignments and can later be used for import /
     * export between profiles.
     */
    private static final Path PLUGIN_DIRECTORY =
        RUNELITE_DIR.toPath().resolve("gearflash");

    private static final Path SAVE_DIRECTORY =
        PLUGIN_DIRECTORY.resolve("data");

    private static final Path TRIGGERS_FILE =
        SAVE_DIRECTORY.resolve("triggers.json");

    private static final Path STYLE_GROUPS_FILE =
        SAVE_DIRECTORY.resolve("style-groups.json");

    private static final String MELEE_GEAR_KEY = "meleeGear";
    private static final String RANGE_GEAR_KEY = "rangeGear";
    private static final String MAGE_GEAR_KEY = "mageGear";

    private static final String MELEE_TRIGGER_KEY = "meleeTrigger";
    private static final String RANGE_TRIGGER_KEY = "rangeTrigger";
    private static final String MAGE_TRIGGER_KEY = "mageTrigger";

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private GearFlashConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private GearFlashOverlay overlay;

    private final Map<Integer, String> itemNames = new HashMap<>();

    private final EnumMap<GearStyle, Set<Integer>> gearGroups =
        new EnumMap<>(GearStyle.class);

    private final EnumMap<GearStyle, Set<Integer>> triggerGroups =
        new EnumMap<>(GearStyle.class);

    private GearStyle activeStyle = GearStyle.NONE;

    @Provides
    GearFlashConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GearFlashConfig.class);
    }

    @Override
    protected void startUp()
    {
        /*
         * Load normal RuneLite profile data first, then restore the JSON file.
         * JSON acts as the persistent fallback so assignments survive a login,
         * profile change, or an empty RS-profile configuration.
         */
        loadAll();
        loadJsonBackup();
        saveAll();

        overlayManager.add(overlay);

        clientThread.invokeLater(() ->
        {
            resolveKnownItemNames();
            updateActiveStyle();
            refreshConfigSummaries();
            writeJsonBackup();
        });
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);

        activeStyle = GearStyle.NONE;
        gearGroups.clear();
        triggerGroups.clear();
        itemNames.clear();
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged event)
    {
        /*
         * A RuneScape profile change can expose an empty set of per-profile
         * values. Restore the persistent JSON immediately, then mirror it back
         * into the active RuneLite profile.
         */
        loadAll();
        loadJsonBackup();
        saveAll();

        clientThread.invokeLater(() ->
        {
            resolveKnownItemNames();
            updateActiveStyle();
            refreshConfigSummaries();
            writeJsonBackup();
        });
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.WORN)
        {
            updateActiveStyle();
        }
    }

    /**
     * Add Shift-only configuration menu after the normal menu is built.
     */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event)
    {
        if (!config.enabled() || client.isMenuOpen())
        {
            return;
        }

        if (!client.isKeyPressed(KeyCode.KC_SHIFT))
        {
            return;
        }

        MenuEntry source = findInventoryEntry();
        if (source == null)
        {
            return;
        }

        final int itemId = source.getItemId();
        final String target = source.getTarget();

        createGearGroupMenu(itemId, target);
        createTriggerMenu(itemId, target);

        if (getGearGroup(itemId) != GearStyle.NONE
            || getTriggerGroup(itemId) != GearStyle.NONE)
        {
            client.createMenuEntry(-2)
                .setOption("Clear Gear Flash settings")
                .setTarget(target)
                .setType(MenuAction.RUNELITE)
                .onClick(e -> clearItem(itemId));
        }
    }

    private MenuEntry findInventoryEntry()
    {
        final MenuEntry[] entries = client.getMenuEntries();

        if (entries == null || entries.length == 0)
        {
            return null;
        }

        for (int i = entries.length - 1; i >= 0; i--)
        {
            final MenuEntry entry = entries[i];

            if (entry == null || entry.getItemId() <= 0)
            {
                continue;
            }

            final int interfaceId =
                WidgetUtil.componentToInterface(entry.getParam1());

            if (interfaceId == InterfaceID.INVENTORY)
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * Compact submenu:
     *
     * Set trigger group >
     *     Melee
     *     Range
     *     Mage
     */
    private void createGearGroupMenu(int itemId, String target)
    {
        final MenuEntry parent = client.createMenuEntry(-2)
            .setOption("GF Style Group")
            .setTarget(target)
            .setType(MenuAction.RUNELITE);

        final Menu submenu = parent.createSubMenu();

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.MELEE,
            "Melee",
            config.meleeColor(),
            false
        );

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.RANGE,
            "Range",
            config.rangeColor(),
            false
        );

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.MAGE,
            "Mage",
            config.mageColor(),
            false
        );

        if (getGearGroup(itemId) != GearStyle.NONE)
        {
            submenu.createMenuEntry(-1)
                .setOption("Remove GF Style Group")
                .setType(MenuAction.RUNELITE)
                .onClick(e -> removeGearGroup(itemId));
        }
    }

    /**
     * Compact submenu:
     *
     * Set trigger >
     *     Melee
     *     Range
     *     Mage
     */
    private void createTriggerMenu(int itemId, String target)
    {
        final MenuEntry parent = client.createMenuEntry(-2)
            .setOption("GF Trigger")
            .setTarget(target)
            .setType(MenuAction.RUNELITE);

        final Menu submenu = parent.createSubMenu();

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.MELEE,
            "Melee",
            config.meleeColor(),
            true
        );

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.RANGE,
            "Range",
            config.rangeColor(),
            true
        );

        addStyleEntry(
            submenu,
            itemId,
            GearStyle.MAGE,
            "Mage",
            config.mageColor(),
            true
        );

        if (getTriggerGroup(itemId) != GearStyle.NONE)
        {
            submenu.createMenuEntry(-1)
                .setOption("Remove GF Trigger")
                .setType(MenuAction.RUNELITE)
                .onClick(e -> removeTrigger(itemId));
        }
    }

    /**
     * RuneLite menu option text supports color tags.
     * RuneLite itself uses ColorUtil.prependColorTag(...) for this.
     */
    private void addStyleEntry(
        Menu submenu,
        int itemId,
        GearStyle style,
        String text,
        Color color,
        boolean trigger)
    {
        final boolean assigned =
            trigger
                ? getSet(triggerGroups, style).contains(itemId)
                : getSet(gearGroups, style).contains(itemId);

        String label = text;

        if (assigned)
        {
            label += " ✓";
        }

        submenu.createMenuEntry(-1)
            .setOption(label)
            .setType(MenuAction.RUNELITE)
            .onClick(e ->
            {
                if (trigger)
                {
                    setTrigger(itemId, style);
                }
                else
                {
                    setGearGroup(itemId, style);
                }
            });
    }

    /**
     * Gear assignment:
     * one item can belong to multiple gear styles.
     */
    private void setGearGroup(int itemId, GearStyle style)
    {
        rememberItemName(itemId);

        /*
         * Style-group membership is independent per combat style.
         *
         * Example:
         * Amulet of fury can be in both Melee and Range.
         * Clicking Melee toggles only Melee; it does not remove Range/Mage.
         */
        final Set<Integer> styleItems = getSet(gearGroups, style);

        if (!styleItems.remove(itemId))
        {
            styleItems.add(itemId);
        }

        saveAll();
        writeJsonBackup();
    }

    private void removeGearGroup(int itemId)
    {
        removeFromMap(gearGroups, itemId);
        saveAll();
        writeJsonBackup();
    }

    /**
     * Trigger assignment:
     * one item can belong to multiple trigger styles.
     *
     * Multiple DIFFERENT items can all be triggers for the same style.
     */
    private void setTrigger(int itemId, GearStyle style)
    {
        rememberItemName(itemId);

        /*
         * Trigger membership is independent too.
         * An item may be configured as more than one trigger style.
         */
        final Set<Integer> styleTriggers = getSet(triggerGroups, style);

        if (!styleTriggers.remove(itemId))
        {
            styleTriggers.add(itemId);
        }

        saveAll();
        writeJsonBackup();

        clientThread.invokeLater(this::updateActiveStyle);
    }

    private void removeTrigger(int itemId)
    {
        removeFromMap(triggerGroups, itemId);
        saveAll();
        writeJsonBackup();

        clientThread.invokeLater(() ->
        {
            updateActiveStyle();
        });
    }

    private void clearItem(int itemId)
    {
        removeFromMap(gearGroups, itemId);
        removeFromMap(triggerGroups, itemId);
        saveAll();
        writeJsonBackup();

        clientThread.invokeLater(() ->
        {
            updateActiveStyle();
        });
    }

    /**
     * Only the equipped WEAPON's TRIGGER assignment activates a style.
     *
     * Normal gear grouping has no effect on activation.
     */
    private void updateActiveStyle()
    {
        if (!config.enabled())
        {
            activeStyle = GearStyle.NONE;
            return;
        }

        final ItemContainer equipment =
            client.getItemContainer(InventoryID.WORN);

        if (equipment == null)
        {
            activeStyle = GearStyle.NONE;
            return;
        }

        final Item[] items = equipment.getItems();
        final int weaponSlot =
            EquipmentInventorySlot.WEAPON.getSlotIdx();

        if (items == null
            || weaponSlot < 0
            || weaponSlot >= items.length)
        {
            activeStyle = GearStyle.NONE;
            return;
        }

        final Item weapon = items[weaponSlot];

        if (weapon == null || weapon.getId() <= 0)
        {
            activeStyle = GearStyle.NONE;
            return;
        }

        final int weaponId = weapon.getId();

        /*
         * A trigger can belong to multiple styles. The overlay currently has
         * one active style/color at a time, so if the SAME equipped item is
         * intentionally assigned to more than one trigger style we use this
         * deterministic priority:
         *
         * Melee -> Range -> Mage
         */
        if (getSet(triggerGroups, GearStyle.MELEE).contains(weaponId))
        {
            activeStyle = GearStyle.MELEE;
        }
        else if (getSet(triggerGroups, GearStyle.RANGE).contains(weaponId))
        {
            activeStyle = GearStyle.RANGE;
        }
        else if (getSet(triggerGroups, GearStyle.MAGE).contains(weaponId))
        {
            activeStyle = GearStyle.MAGE;
        }
        else
        {
            activeStyle = GearStyle.NONE;
        }
    }

    /**
     * Only items in the ACTIVE GEAR GROUP flash.
     *
     * Trigger weapons are stored separately, so having several melee trigger
     * weapons will not make all those weapons flash.
     */
    boolean shouldHighlight(int itemId)
    {
        return config.enabled()
            && activeStyle != GearStyle.NONE
            && getSet(gearGroups, activeStyle).contains(itemId);
    }

    GearStyle getActiveStyle()
    {
        return activeStyle;
    }

    private GearStyle getGearGroup(int itemId)
    {
        return getStyleForMap(gearGroups, itemId);
    }

    private GearStyle getTriggerGroup(int itemId)
    {
        return getStyleForMap(triggerGroups, itemId);
    }

    private GearStyle getStyleForMap(
        EnumMap<GearStyle, Set<Integer>> map,
        int itemId)
    {
        if (getSet(map, GearStyle.MELEE).contains(itemId))
        {
            return GearStyle.MELEE;
        }

        if (getSet(map, GearStyle.RANGE).contains(itemId))
        {
            return GearStyle.RANGE;
        }

        if (getSet(map, GearStyle.MAGE).contains(itemId))
        {
            return GearStyle.MAGE;
        }

        return GearStyle.NONE;
    }

    private void removeFromMap(
        EnumMap<GearStyle, Set<Integer>> map,
        int itemId)
    {
        getSet(map, GearStyle.MELEE).remove(itemId);
        getSet(map, GearStyle.RANGE).remove(itemId);
        getSet(map, GearStyle.MAGE).remove(itemId);
    }

    private Set<Integer> getSet(
        EnumMap<GearStyle, Set<Integer>> map,
        GearStyle style)
    {
        return map.computeIfAbsent(style, ignored -> new HashSet<>());
    }

    private void loadAll()
    {
        gearGroups.clear();
        triggerGroups.clear();

        gearGroups.put(
            GearStyle.MELEE,
            loadSet(MELEE_GEAR_KEY)
        );
        gearGroups.put(
            GearStyle.RANGE,
            loadSet(RANGE_GEAR_KEY)
        );
        gearGroups.put(
            GearStyle.MAGE,
            loadSet(MAGE_GEAR_KEY)
        );

        triggerGroups.put(
            GearStyle.MELEE,
            loadSet(MELEE_TRIGGER_KEY)
        );
        triggerGroups.put(
            GearStyle.RANGE,
            loadSet(RANGE_TRIGGER_KEY)
        );
        triggerGroups.put(
            GearStyle.MAGE,
            loadSet(MAGE_TRIGGER_KEY)
        );
    }

    private Set<Integer> loadSet(String key)
    {
        final String raw =
            configManager.getRSProfileConfiguration(
                STORAGE_GROUP,
                key
            );

        if (raw == null || raw.trim().isEmpty())
        {
            return new HashSet<>();
        }

        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s ->
            {
                try
                {
                    return Integer.parseInt(s);
                }
                catch (NumberFormatException ex)
                {
                    return -1;
                }
            })
            .filter(id -> id > 0)
            .collect(Collectors.toCollection(HashSet::new));
    }


    /**
     * Snapshot used by the Swing management panel.
     */
    Set<Integer> getTriggerItems(GearStyle style)
    {
        return Collections.unmodifiableSet(
            new HashSet<>(getSet(triggerGroups, style))
        );
    }

    /**
     * Snapshot used by the Swing management panel.
     *
     * Internal variable name is gearGroups; user-facing name is Trigger Group.
     */
    Set<Integer> getTriggerGroupItems(GearStyle style)
    {
        return Collections.unmodifiableSet(
            new HashSet<>(getSet(gearGroups, style))
        );
    }

    String getItemName(int itemId)
    {
        return itemNames.getOrDefault(itemId, "Item " + itemId);
    }

    void removeTriggerFromManager(int itemId)
    {
        removeTrigger(itemId);
    }

    void removeTriggerGroupFromManager(int itemId)
    {
        removeGearGroup(itemId);
    }

    /**
     * Must run on the RuneLite client thread because ItemManager ultimately
     * reads item definitions from the game client.
     */
    private void resolveKnownItemNames()
    {
        Set<Integer> all = new HashSet<>();

        for (GearStyle style : new GearStyle[]{
            GearStyle.MELEE,
            GearStyle.RANGE,
            GearStyle.MAGE})
        {
            all.addAll(getSet(triggerGroups, style));
            all.addAll(getSet(gearGroups, style));
        }

        for (int itemId : all)
        {
            rememberItemName(itemId);
        }
    }

    private void rememberItemName(int itemId)
    {
        try
        {
            String name = itemManager.getItemComposition(itemId).getName();

            if (name != null && !name.trim().isEmpty())
            {
                itemNames.put(itemId, name);
            }
        }
        catch (Exception ignored)
        {
            itemNames.putIfAbsent(itemId, "Item " + itemId);
        }
    }

    private void refreshConfigSummaries()
    {
        /*
         * These are display-only copies used by RuneLite's normal ConfigPanel.
         * The real assignment data remains in gearGroups/triggerGroups and is
         * stored in RS-profile config.
         */
        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "meleeTriggersSummary",
            buildSummary(getSet(triggerGroups, GearStyle.MELEE))
        );
        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "meleeGroupSummary",
            buildSummary(getSet(gearGroups, GearStyle.MELEE))
        );

        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "rangeTriggersSummary",
            buildSummary(getSet(triggerGroups, GearStyle.RANGE))
        );
        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "rangeGroupSummary",
            buildSummary(getSet(gearGroups, GearStyle.RANGE))
        );

        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "mageTriggersSummary",
            buildSummary(getSet(triggerGroups, GearStyle.MAGE))
        );
        configManager.setConfiguration(
            GearFlashConfig.GROUP,
            "mageGroupSummary",
            buildSummary(getSet(gearGroups, GearStyle.MAGE))
        );
    }

    private String buildSummary(Set<Integer> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return "None";
        }

        return ids.stream()
            .map(this::getItemName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(Collectors.joining(", "));
    }


    /**
     * Write a human-readable JSON mirror of the current assignments.
     *
     * Item ID is authoritative. Item name is included only to make the file
     * pleasant to inspect/edit by hand.
     *
     * Example:
     * {
     *   "id": 13576,
     *   "name": "Dragon warhammer"
     * }
     */
    /**
     * Restore assignments from the persistent JSON file.
     *
     * JSON uses item IDs as the authoritative values. Names are only metadata.
     * Invalid/missing entries are ignored rather than preventing the plugin
     * from starting.
     */
    /**
     * Load the two persistent Gear Flash assignment files.
     */
    private void loadJsonBackup()
    {
        try
        {
            Files.createDirectories(SAVE_DIRECTORY);

            loadAssignmentFile(TRIGGERS_FILE, triggerGroups);
            loadAssignmentFile(STYLE_GROUPS_FILE, gearGroups);

            log.debug(
                "Gear Flash JSON loaded from {}",
                SAVE_DIRECTORY.toAbsolutePath()
            );
        }
        catch (Exception ex)
        {
            log.warn(
                "Unable to load Gear Flash JSON from {}",
                SAVE_DIRECTORY.toAbsolutePath(),
                ex
            );
        }
    }

    private void loadAssignmentFile(
        Path file,
        EnumMap<GearStyle, Set<Integer>> destination)
        throws IOException
    {
        if (!Files.exists(file))
        {
            return;
        }

        final String json = new String(
            Files.readAllBytes(file),
            StandardCharsets.UTF_8
        );

        final JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root == null)
        {
            return;
        }

        destination.put(GearStyle.MELEE, readJsonItemIds(root.get("melee")));
        destination.put(GearStyle.RANGE, readJsonItemIds(root.get("range")));
        destination.put(GearStyle.MAGE, readJsonItemIds(root.get("mage")));
    }

    private Set<Integer> readJsonItemIds(JsonElement element)
    {
        final Set<Integer> result = new HashSet<>();

        if (element == null || !element.isJsonArray())
        {
            return result;
        }

        for (JsonElement entry : element.getAsJsonArray())
        {
            if (entry == null || !entry.isJsonObject())
            {
                continue;
            }

            final JsonObject item = entry.getAsJsonObject();

            if (!item.has("id"))
            {
                continue;
            }

            try
            {
                final int itemId = item.get("id").getAsInt();

                if (itemId > 0)
                {
                    result.add(itemId);

                    if (item.has("name"))
                    {
                        final String savedName = item.get("name").getAsString();
                        if (savedName != null && !savedName.trim().isEmpty())
                        {
                            itemNames.put(itemId, savedName);
                        }
                    }
                }
            }
            catch (Exception ignored)
            {
                // Ignore a malformed individual item.
            }
        }

        return result;
    }

    /**
     * Rewrite both assignment files after every Gear Flash assignment change.
     */
    private void writeJsonBackup()
    {
        try
        {
            Files.createDirectories(SAVE_DIRECTORY);

            writeAssignmentFile(TRIGGERS_FILE, triggerGroups);
            writeAssignmentFile(STYLE_GROUPS_FILE, gearGroups);

            log.debug(
                "Gear Flash JSON saved to {}",
                SAVE_DIRECTORY.toAbsolutePath()
            );
        }
        catch (IOException ex)
        {
            log.warn(
                "Unable to write Gear Flash JSON files to {}",
                SAVE_DIRECTORY.toAbsolutePath(),
                ex
            );
        }
    }

    private void writeAssignmentFile(
        Path file,
        EnumMap<GearStyle, Set<Integer>> source)
        throws IOException
    {
        final JsonObject root = new JsonObject();
        root.addProperty("version", SAVE_FILE_VERSION);

        root.add("melee", buildJsonItemArray(getSet(source, GearStyle.MELEE)));
        root.add("range", buildJsonItemArray(getSet(source, GearStyle.RANGE)));
        root.add("mage", buildJsonItemArray(getSet(source, GearStyle.MAGE)));

        final Path temporaryFile =
            file.resolveSibling(file.getFileName().toString() + ".tmp");

        Files.write(
            temporaryFile,
            GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );

        try
        {
            Files.move(
                temporaryFile,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        }
        catch (java.nio.file.AtomicMoveNotSupportedException ex)
        {
            Files.move(
                temporaryFile,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private JsonArray buildJsonItemArray(Set<Integer> ids)
    {
        JsonArray array = new JsonArray();

        ids.stream()
            .sorted((a, b) ->
                getItemName(a).compareToIgnoreCase(getItemName(b)))
            .forEach(itemId ->
            {
                JsonObject item = new JsonObject();
                item.addProperty("id", itemId);
                item.addProperty("name", getItemName(itemId));
                array.add(item);
            });

        return array;
    }

    private void saveAll()
    {
        saveSet(
            MELEE_GEAR_KEY,
            getSet(gearGroups, GearStyle.MELEE)
        );
        saveSet(
            RANGE_GEAR_KEY,
            getSet(gearGroups, GearStyle.RANGE)
        );
        saveSet(
            MAGE_GEAR_KEY,
            getSet(gearGroups, GearStyle.MAGE)
        );

        saveSet(
            MELEE_TRIGGER_KEY,
            getSet(triggerGroups, GearStyle.MELEE)
        );
        saveSet(
            RANGE_TRIGGER_KEY,
            getSet(triggerGroups, GearStyle.RANGE)
        );
        saveSet(
            MAGE_TRIGGER_KEY,
            getSet(triggerGroups, GearStyle.MAGE)
        );

        /*
         * Keep Configuration -> Gear Flash summaries in sync whenever an
         * assignment changes.
         */
        refreshConfigSummaries();
    }

    private void saveSet(String key, Set<Integer> set)
    {
        if (set.isEmpty())
        {
            configManager.unsetRSProfileConfiguration(
                STORAGE_GROUP,
                key
            );
            return;
        }

        final String csv = set.stream()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(","));

        configManager.setRSProfileConfiguration(
            STORAGE_GROUP,
            key,
            csv
        );
    }
}
