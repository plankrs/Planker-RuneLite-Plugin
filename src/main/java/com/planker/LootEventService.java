package com.planker;

import com.planker.model.PlankerEvent;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

@Slf4j
public class LootEventService
{
    private static final long DUPLICATE_WINDOW_MS = 3_000L;
    private static final long DEDUP_ENTRY_MAX_AGE_MS = 30_000L;
    private static final int MAX_IMAGE_BYTES = 1_000_000;

    private final PlankerConfig config;
    private final EventQueue eventQueue;
    private final ItemManager itemManager;
    private final EventImageService imageService;
    private final NotificationPolicyService policyService;
    private final Map<String, Long> recentlyProcessedDrops = new HashMap<>();

    @Inject
    public LootEventService(PlankerConfig config, EventQueue eventQueue, ItemManager itemManager,
        EventImageService imageService, NotificationPolicyService policyService)
    {
        this.config = config;
        this.eventQueue = eventQueue;
        this.itemManager = itemManager;
        this.imageService = imageService;
        this.policyService = policyService;
    }

    public void processNpcLoot(String loggedInRsn, String npcName, Collection<ItemStack> lootStacks)
    {
        if (!config.enableDropReporting() || isBlank(loggedInRsn)
            || lootStacks == null || lootStacks.isEmpty())
        {
            return;
        }

        String lootSourceName = isBlank(npcName) ? "Unknown NPC" : npcName.trim();
        long minimumDropValueGp = Math.max(0L, policyService.minimumDropValueGp());
        long currentTime = System.currentTimeMillis();
        removeExpiredDedupEntries(currentTime);

        for (ItemStack lootStack : lootStacks)
        {
            if (lootStack == null || lootStack.getQuantity() <= 0 || lootStack.getId() <= 0)
            {
                continue;
            }

            int canonicalItemId = itemManager.canonicalize(lootStack.getId());
            if (canonicalItemId <= 0)
            {
                continue;
            }

            int itemQuantity = lootStack.getQuantity();
            ItemComposition itemComposition = itemManager.getItemComposition(canonicalItemId);
            String itemName = itemComposition == null || isBlank(itemComposition.getName())
                ? "Unknown item"
                : itemComposition.getName().trim();
            int unitPriceGp = Math.max(0, itemManager.getItemPrice(canonicalItemId));
            long totalStackValueGp = (long) unitPriceGp * itemQuantity;

            if (totalStackValueGp < minimumDropValueGp)
            {
                log.debug("Skipping {} x {} from {}: value {} is below threshold {}",
                    itemQuantity, itemName, lootSourceName, totalStackValueGp, minimumDropValueGp);
                continue;
            }

            String dropFingerprint = buildDropFingerprint(
                loggedInRsn, lootSourceName, canonicalItemId, itemQuantity, totalStackValueGp);
            Long previouslyProcessedAt = recentlyProcessedDrops.get(dropFingerprint);
            if (previouslyProcessedAt != null && currentTime - previouslyProcessedAt <= DUPLICATE_WINDOW_MS)
            {
                log.debug("Ignoring duplicate loot callback for itemId={} quantity={} source={}",
                    canonicalItemId, itemQuantity, lootSourceName);
                continue;
            }
            recentlyProcessedDrops.put(dropFingerprint, currentTime);

            Map<String, Object> dropPayload = new LinkedHashMap<>();
            dropPayload.put("itemId", canonicalItemId);
            dropPayload.put("itemName", itemName);
            dropPayload.put("quantity", itemQuantity);
            dropPayload.put("unitPrice", unitPriceGp);
            dropPayload.put("totalValue", totalStackValueGp);
            dropPayload.put("npcName", lootSourceName);

            if (config.attachItemIcons())
            {
                addItemImages(dropPayload, canonicalItemId, itemQuantity);
            }

            Runnable enqueueDrop = () ->
            {
                eventQueue.enqueue(PlankerEvent.builder()
                    .eventType("LOOT_DROP")
                    .rsn(loggedInRsn.trim())
                    .data(dropPayload)
                    .build());
                log.debug("Queued qualifying drop: itemId={} quantity={} totalValue={}",
                    canonicalItemId, itemQuantity, totalStackValueGp);
            };

            boolean includeScreenshot = config.attachScreenshots()
                && config.screenshotDrops()
                && policyService.screenshotsEnabled()
                && totalStackValueGp >= policyService.minimumDropScreenshotValueGp();
            if (includeScreenshot)
            {
                imageService.addScreenshotAsync(dropPayload, enqueueDrop);
            }
            else
            {
                enqueueDrop.run();
            }
        }
    }

    private void addItemImages(Map<String, Object> dropPayload, int itemId, int quantity)
    {
        try
        {
            BufferedImage sourceItemImage = itemManager.getImage(itemId, quantity, false);
            if (sourceItemImage == null)
            {
                return;
            }

            putEncodedImage(dropPayload, "itemIconBase64", sourceItemImage);
            putEncodedImage(dropPayload, "itemLargeBase64", createCenteredCanvas(sourceItemImage, 256, 5));
            putEncodedImage(dropPayload, "itemHeroBase64", createCenteredCanvas(sourceItemImage, 512, 9));
        }
        catch (RuntimeException exception)
        {
            log.debug("Unable to generate item imagery for itemId={}: {}", itemId, exception.getMessage());
            log.trace("Item imagery generation failure", exception);
        }
    }

    private BufferedImage createCenteredCanvas(BufferedImage sourceImage, int canvasSize, int maximumScale)
    {
        BufferedImage centeredImage = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = centeredImage.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);

            int scaleByWidth = Math.max(1, (canvasSize - 32) / Math.max(1, sourceImage.getWidth()));
            int scaleByHeight = Math.max(1, (canvasSize - 32) / Math.max(1, sourceImage.getHeight()));
            int selectedScale = Math.max(1,
                Math.min(maximumScale, Math.min(scaleByWidth, scaleByHeight)));
            int renderedWidth = sourceImage.getWidth() * selectedScale;
            int renderedHeight = sourceImage.getHeight() * selectedScale;
            int horizontalOffset = (canvasSize - renderedWidth) / 2;
            int verticalOffset = (canvasSize - renderedHeight) / 2;
            graphics.drawImage(sourceImage, horizontalOffset, verticalOffset,
                renderedWidth, renderedHeight, null);
            return centeredImage;
        }
        finally
        {
            graphics.dispose();
        }
    }

    private void putEncodedImage(Map<String, Object> imagePayload, String payloadKey, BufferedImage image)
    {
        try (ByteArrayOutputStream encodedImageBuffer = new ByteArrayOutputStream())
        {
            if (!ImageIO.write(image, "png", encodedImageBuffer))
            {
                return;
            }
            byte[] pngBytes = encodedImageBuffer.toByteArray();
            if (pngBytes.length == 0 || pngBytes.length > MAX_IMAGE_BYTES)
            {
                log.debug("Skipping {} because its PNG size was {} bytes", payloadKey, pngBytes.length);
                return;
            }
            imagePayload.put(payloadKey, Base64.getEncoder().encodeToString(pngBytes));
        }
        catch (IOException exception)
        {
            log.debug("Unable to encode {}: {}", payloadKey, exception.getMessage());
            log.trace("Image encoding failure", exception);
        }
    }

    private String buildDropFingerprint(String loggedInRsn, String npcName, int itemId,
        int quantity, long totalValueGp)
    {
        return loggedInRsn.toLowerCase(Locale.ROOT) + '|'
            + npcName.toLowerCase(Locale.ROOT) + '|'
            + itemId + '|' + quantity + '|' + totalValueGp;
    }

    private void removeExpiredDedupEntries(long currentTime)
    {
        Iterator<Map.Entry<String, Long>> dropIterator = recentlyProcessedDrops.entrySet().iterator();
        while (dropIterator.hasNext())
        {
            Map.Entry<String, Long> recordedDrop = dropIterator.next();
            if (currentTime - recordedDrop.getValue() > DEDUP_ENTRY_MAX_AGE_MS)
            {
                dropIterator.remove();
            }
        }
    }

    private boolean isBlank(String text)
    {
        return text == null || text.trim().isEmpty();
    }
}
