package com.planker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

@Slf4j
public class EventBadgeRenderer
{
    private static final Color PANEL = new Color(32, 27, 20, 248);
    private static final Color PANEL_LIGHT = new Color(43, 38, 31, 245);
    private static final Color GOLD = new Color(205, 164, 52);
    private static final Color GOLD_LIGHT = new Color(236, 205, 112);
    private static final Color TEXT = new Color(245, 239, 222);
    private static final Color MUTED = new Color(196, 181, 146);
    private final SkillIconManager skillIconManager;

    @Inject
    public EventBadgeRenderer(SkillIconManager skillIconManager)
    {
        this.skillIconManager = skillIconManager;
    }

    public void addSkillBadge(Map<String, Object> eventPayload, Skill skill, int level)
    {
        BufferedImage skillIcon = skillIconManager.getSkillImage(skill);
        putPng(eventPayload, "eventIconBase64", createSkillBadge(128, skillIcon, skill, level));
        putPng(eventPayload, "eventLargeBase64", createSkillBadge(384, skillIcon, skill, level));
        putPng(eventPayload, "eventHeroBase64", createSkillBadge(512, skillIcon, skill, level));
    }

    public void addEventBadge(Map<String, Object> eventPayload, String label, String subtitle)
    {
        putPng(eventPayload, "eventIconBase64", createEventBadge(128, label, subtitle));
        putPng(eventPayload, "eventLargeBase64", createEventBadge(384, label, subtitle));
    }

    private BufferedImage createSkillBadge(int size, BufferedImage icon, Skill skill, int level)
    {
        BufferedImage image = createBaseCard(size);
        Graphics2D graphics = image.createGraphics();
        try
        {
            configureQuality(graphics);
            int iconBox = size >= 300 ? 164 : 56;
            int iconY = size >= 300 ? 34 : 10;
            drawIcon(graphics, icon, size, iconY, iconBox);
            graphics.setColor(GOLD_LIGHT);
            graphics.setFont(new Font(Font.SERIF, Font.BOLD, size >= 300 ? 64 : 25));
            drawCentered(graphics, Integer.toString(level), size, size >= 300 ? 257 : 92);
            graphics.setColor(TEXT);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size >= 300 ? 24 : 11));
            drawCentered(graphics, formatSkill(skill), size, size >= 300 ? 302 : 111);
            if (size >= 300)
            {
                graphics.setColor(MUTED);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                drawCentered(graphics, "LEVEL ACHIEVED", size, 340);
            }
            return image;
        }
        finally
        {
            graphics.dispose();
        }
    }

    private BufferedImage createEventBadge(int size, String label, String subtitle)
    {
        BufferedImage image = createBaseCard(size);
        Graphics2D graphics = image.createGraphics();
        try
        {
            configureQuality(graphics);
            int medallionSize = size >= 300 ? 150 : 54;
            int x = (size - medallionSize) / 2;
            int y = size >= 300 ? 42 : 13;
            graphics.setColor(new Color(18, 16, 13));
            graphics.fillOval(x, y, medallionSize, medallionSize);
            graphics.setStroke(new BasicStroke(Math.max(2f, size / 90f)));
            graphics.setColor(GOLD);
            graphics.drawOval(x, y, medallionSize, medallionSize);
            String cleanLabel = abbreviate(label, size >= 300 ? 10 : 6);
            graphics.setFont(new Font(Font.SERIF, Font.BOLD, size >= 300 ? 48 : 18));
            graphics.setColor(GOLD_LIGHT);
            FontMetrics metrics = graphics.getFontMetrics();
            int baseline = y + (medallionSize - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(cleanLabel, (size - metrics.stringWidth(cleanLabel)) / 2, baseline);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size >= 300 ? 25 : 11));
            graphics.setColor(TEXT);
            drawCentered(graphics, abbreviate(subtitle, size >= 300 ? 26 : 16), size, size >= 300 ? 260 : 94);
            if (size >= 300)
            {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                graphics.setColor(MUTED);
                drawCentered(graphics, "CLAN ACHIEVEMENT", size, 310);
            }
            return image;
        }
        finally
        {
            graphics.dispose();
        }
    }

    private BufferedImage createBaseCard(int size)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            configureQuality(graphics);
            int arc = Math.max(16, size / 10);
            graphics.setColor(PANEL);
            graphics.fillRoundRect(0, 0, size, size, arc, arc);
            int inset = size / 18;
            graphics.setColor(PANEL_LIGHT);
            graphics.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, arc, arc);
            graphics.setStroke(new BasicStroke(Math.max(2f, size / 100f)));
            graphics.setColor(GOLD);
            graphics.drawRoundRect(2, 2, size - 5, size - 5, arc, arc);
            graphics.setStroke(new BasicStroke(Math.max(1f, size / 180f)));
            graphics.setColor(new Color(112, 91, 48, 180));
            graphics.drawRoundRect(inset, inset, size - inset * 2, size - inset * 2, arc, arc);
            return image;
        }
        finally
        {
            graphics.dispose();
        }
    }

    private void drawIcon(Graphics2D graphics, BufferedImage icon, int size, int y, int box)
    {
        if (icon == null) return;
        int iconSize = (int) (box * 0.92);
        int iconX = (size - iconSize) / 2;
        int iconY = y + (box - iconSize) / 2;
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(icon, iconX, iconY, iconSize, iconSize, null);
    }

    private void configureQuality(Graphics2D graphics)
    {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private void drawCentered(Graphics2D graphics, String text, int width, int y)
    {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(text, Math.max(4, (width - metrics.stringWidth(text)) / 2), y);
    }

    private String formatSkill(Skill skill)
    {
        String lower = skill.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String abbreviate(String text, int maximum)
    {
        if (text == null || text.trim().isEmpty()) return "PLANKER";
        String clean = text.trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    private void putPng(Map<String, Object> eventPayload, String key, BufferedImage image)
    {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream())
        {
            if (ImageIO.write(image, "png", bytes) && bytes.size() <= 1_000_000)
            {
                eventPayload.put(key, Base64.getEncoder().encodeToString(bytes.toByteArray()));
            }
        }
        catch (IOException exception)
        {
            log.debug("Event badge encoding failed: {}", exception.getMessage());
            log.trace("Event badge encoding failure details", exception);
        }
    }
}
