package com.gearflash;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

public class GearFlashOverlay extends WidgetItemOverlay
{
    private final GearFlashPlugin plugin;
    private final GearFlashConfig config;

    @Inject
    public GearFlashOverlay(GearFlashPlugin plugin, GearFlashConfig config)
    {
        this.plugin = plugin;
        this.config = config;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (!plugin.shouldHighlight(itemId))
        {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null)
        {
            return;
        }

        Color base = colorFor(plugin.getActiveStyle());
        if (base == null)
        {
            return;
        }

        int alpha = calculateAlpha();
        int fillAlpha = Math.max(0, Math.min(255, alpha / 3));

        int x = bounds.x + 1;
        int y = bounds.y + 1;
        int width = Math.max(1, bounds.width - 3);
        int height = Math.max(1, bounds.height - 3);

        if (config.fillItem())
        {
            graphics.setColor(new Color(
                base.getRed(), base.getGreen(), base.getBlue(), fillAlpha));
            graphics.fillRoundRect(x, y, width, height, 8, 8);
        }

        graphics.setStroke(new BasicStroke(config.borderWidth()));
        graphics.setColor(new Color(
            base.getRed(), base.getGreen(), base.getBlue(), alpha));
        graphics.drawRoundRect(x, y, width, height, 8, 8);
    }

    private Color colorFor(GearStyle style)
    {
        switch (style)
        {
            case MELEE:
                return config.meleeColor();
            case RANGE:
                return config.rangeColor();
            case MAGE:
                return config.mageColor();
            default:
                return null;
        }
    }

    private int calculateAlpha()
    {
        int min = Math.max(0, Math.min(255, config.minimumAlpha()));
        int max = Math.max(0, Math.min(255, config.maximumAlpha()));

        if (min > max)
        {
            int tmp = min;
            min = max;
            max = tmp;
        }

        if (!config.flash())
        {
            return max;
        }

        int period = Math.max(150, config.flashPeriod());
        double phase = (System.currentTimeMillis() % period) / (double) period;
        double pulse =
            (Math.sin((phase * Math.PI * 2.0) - (Math.PI / 2.0)) + 1.0) / 2.0;

        return (int) Math.round(min + ((max - min) * pulse));
    }
}
