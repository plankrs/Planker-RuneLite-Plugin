package com.planker;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

@Slf4j
public class ScreenshotCaptureService
{
    private static final int MAX_IMAGE_BYTES = 7_500_000;
    private static final int MAX_PENDING_SCREENSHOTS = 100;
    private static final int MAXIMUM_WIDTH = 2560;
    private static final float JPEG_QUALITY = 0.95f;
    private final DrawManager drawManager;
    private final ScheduledExecutorService executor;
    private final PlankerConfig config;
    private final List<ScreenshotRequest> pendingRequests = new ArrayList<>();
    private boolean captureInProgress;
    private String cachedScreenshotBase64;
    private long cachedScreenshotAt;

    @Inject
    public ScreenshotCaptureService(DrawManager drawManager, ScheduledExecutorService executor, PlankerConfig config)
    {
        this.drawManager = drawManager;
        this.executor = executor;
        this.config = config;
    }

    public void addScreenshotAsync(Map<String, Object> eventPayload, Runnable completionAction)
    {
        if (eventPayload == null || completionAction == null)
        {
            log.warn("Ignored an invalid screenshot request");
            return;
        }
        String reusableScreenshot;
        synchronized (this)
        {
            long now = System.currentTimeMillis();
            long cooldownMs = Math.max(0, config.screenshotCooldownSeconds()) * 1_000L;
            reusableScreenshot = cachedScreenshotBase64 != null && now - cachedScreenshotAt <= cooldownMs ? cachedScreenshotBase64 : null;
            if (reusableScreenshot == null)
            {
                if (pendingRequests.size() >= MAX_PENDING_SCREENSHOTS)
                {
                    log.warn("Screenshot request queue reached capacity; sending event without a screenshot");
                    completionAction.run();
                    return;
                }
                pendingRequests.add(new ScreenshotRequest(eventPayload, completionAction));
                if (captureInProgress) return;
                captureInProgress = true;
            }
        }
        if (reusableScreenshot != null)
        {
            eventPayload.put("screenshotBase64", reusableScreenshot);
            completionAction.run();
            return;
        }
        try
        {
            drawManager.requestNextFrameListener(frame -> executor.execute(() -> finishCapture(copyFrame(frame))));
        }
        catch (RuntimeException exception)
        {
            log.warn("RuneLite frame capture could not be requested: {}", exception.getMessage());
            log.debug("Frame capture request failure details", exception);
            finishCapture(null);
        }
    }

    private BufferedImage copyFrame(Image frame)
    {
        if (frame == null || frame.getWidth(null) <= 0 || frame.getHeight(null) <= 0) return null;
        BufferedImage copy = new BufferedImage(frame.getWidth(null), frame.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try { graphics.drawImage(frame, 0, 0, null); return copy; }
        finally { graphics.dispose(); }
    }

    private void finishCapture(BufferedImage source)
    {
        String encoded = null;
        try
        {
            if (source != null) encoded = encodeJpeg(resize(source));
        }
        catch (RuntimeException | IOException exception)
        {
            log.warn("RuneLite screenshot encoding failed: {}", exception.getMessage());
            log.debug("Screenshot encoding failure details", exception);
        }
        List<ScreenshotRequest> requests;
        synchronized (this)
        {
            if (encoded != null) { cachedScreenshotBase64 = encoded; cachedScreenshotAt = System.currentTimeMillis(); }
            requests = new ArrayList<>(pendingRequests);
            pendingRequests.clear();
            captureInProgress = false;
        }
        for (ScreenshotRequest request : requests)
        {
            if (encoded != null) request.eventPayload.put("screenshotBase64", encoded);
            try { request.completionAction.run(); }
            catch (RuntimeException exception) { log.error("Screenshot completion callback failed: {}", exception.getMessage()); log.debug("Screenshot completion callback failure details", exception); }
        }
    }

    private BufferedImage resize(BufferedImage source)
    {
        if (source.getWidth() <= MAXIMUM_WIDTH) return source;
        int height = (int) Math.round((double) source.getHeight() * MAXIMUM_WIDTH / source.getWidth());
        BufferedImage output = new BufferedImage(MAXIMUM_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            graphics.drawImage(source, 0, 0, MAXIMUM_WIDTH, height, null);
            return output;
        }
        finally { graphics.dispose(); }
    }

    private String encodeJpeg(BufferedImage image) throws IOException
    {
        Iterator<ImageWriter> jpegWriters = ImageIO.getImageWritersByFormatName("jpg");
        if (!jpegWriters.hasNext()) { log.warn("No JPEG encoder is available; sending event without a screenshot"); return null; }
        ImageWriter writer = jpegWriters.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream imageOutput = ImageIO.createImageOutputStream(bytes))
        {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) { parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(JPEG_QUALITY); }
            writer.write(null, new IIOImage(image, null, null), parameters);
            byte[] jpg = bytes.toByteArray();
            if (jpg.length == 0 || jpg.length > MAX_IMAGE_BYTES) { log.debug("Skipping screenshot because encoded JPEG size was {} bytes", jpg.length); return null; }
            return Base64.getEncoder().encodeToString(jpg);
        }
        finally { writer.dispose(); }
    }

    private static final class ScreenshotRequest
    {
        private final Map<String, Object> eventPayload;
        private final Runnable completionAction;
        private ScreenshotRequest(Map<String, Object> eventPayload, Runnable completionAction) { this.eventPayload = eventPayload; this.completionAction = completionAction; }
    }
}
