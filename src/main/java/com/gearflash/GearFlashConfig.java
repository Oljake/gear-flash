package com.gearflash;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(GearFlashConfig.GROUP)
public interface GearFlashConfig extends Config
{
    String GROUP = "gearflash";


    @ConfigSection(
        name = "Melee",
        description = "Melee trigger and trigger-group assignments.",
        position = 100,
        closedByDefault = true
    )
    String meleeAssignmentsSection = "meleeAssignments";

    @ConfigSection(
        name = "Range",
        description = "Range trigger and trigger-group assignments.",
        position = 101,
        closedByDefault = true
    )
    String rangeAssignmentsSection = "rangeAssignments";

    @ConfigSection(
        name = "Mage",
        description = "Mage trigger and trigger-group assignments.",
        position = 102,
        closedByDefault = true
    )
    String mageAssignmentsSection = "mageAssignments";

    /*
     * These six fields are display summaries maintained automatically by the
     * plugin. The real assignments are still stored separately in the RS
     * profile configuration.
     */

    @ConfigItem(
        keyName = "meleeTriggersSummary",
        name = "Triggers",
        description = "Weapons assigned as Melee triggers. Manage individual items from the Gear Flash sidebar.",
        section = meleeAssignmentsSection,
        position = 0
    )
    default String meleeTriggersSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "meleeGroupSummary",
        name = "Style Group",
        description = "Items assigned to the Melee trigger group. Manage individual items from the Gear Flash sidebar.",
        section = meleeAssignmentsSection,
        position = 1
    )
    default String meleeGroupSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "rangeTriggersSummary",
        name = "Triggers",
        description = "Weapons assigned as Range triggers. Manage individual items from the Gear Flash sidebar.",
        section = rangeAssignmentsSection,
        position = 0
    )
    default String rangeTriggersSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "rangeGroupSummary",
        name = "Style Group",
        description = "Items assigned to the Range trigger group. Manage individual items from the Gear Flash sidebar.",
        section = rangeAssignmentsSection,
        position = 1
    )
    default String rangeGroupSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "mageTriggersSummary",
        name = "Triggers",
        description = "Weapons assigned as Mage triggers. Manage individual items from the Gear Flash sidebar.",
        section = mageAssignmentsSection,
        position = 0
    )
    default String mageTriggersSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "mageGroupSummary",
        name = "Style Group",
        description = "Items assigned to the Mage trigger group. Manage individual items from the Gear Flash sidebar.",
        section = mageAssignmentsSection,
        position = 1
    )
    default String mageGroupSummary()
    {
        return "None";
    }

    @ConfigItem(
        keyName = "enabled",
        name = "Enable",
        description = "Enable Gear Flash."
    )
    default boolean enabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "flash",
        name = "Pulse highlight",
        description = "Pulse the highlighted gear instead of showing a static highlight.",
        position = 10
    )
    default boolean flash()
    {
        return true;
    }

    @Range(min = 150, max = 3000)
    @ConfigItem(
        keyName = "flashPeriod",
        name = "Pulse speed (ms)",
        description = "Length of one full pulse.",
        position = 11
    )
    default int flashPeriod()
    {
        return 650;
    }

    @Range(min = 0, max = 255)
    @ConfigItem(
        keyName = "minimumAlpha",
        name = "Minimum opacity",
        description = "Lowest highlight opacity during the pulse.",
        position = 12
    )
    default int minimumAlpha()
    {
        return 35;
    }

    @Range(min = 0, max = 255)
    @ConfigItem(
        keyName = "maximumAlpha",
        name = "Maximum opacity",
        description = "Highest highlight opacity during the pulse.",
        position = 13
    )
    default int maximumAlpha()
    {
        return 190;
    }

    @Range(min = 1, max = 6)
    @ConfigItem(
        keyName = "borderWidth",
        name = "Border width",
        description = "Thickness of the item border.",
        position = 14
    )
    default int borderWidth()
    {
        return 2;
    }

    @ConfigItem(
        keyName = "fillItem",
        name = "Fill item slot",
        description = "Draw a translucent fill over the selected item as well as the border.",
        position = 15
    )
    default boolean fillItem()
    {
        return true;
    }

    @ConfigItem(
        keyName = "meleeColor",
        name = "Melee color",
        description = "Highlight/menu color for melee.",
        position = 20
    )
    default Color meleeColor()
    {
        return Color.RED;
    }

    @ConfigItem(
        keyName = "rangeColor",
        name = "Range color",
        description = "Highlight/menu color for range.",
        position = 21
    )
    default Color rangeColor()
    {
        return Color.GREEN;
    }

    @ConfigItem(
        keyName = "mageColor",
        name = "Mage color",
        description = "Highlight/menu color for mage.",
        position = 22
    )
    default Color mageColor()
    {
        return Color.CYAN;
    }
}
