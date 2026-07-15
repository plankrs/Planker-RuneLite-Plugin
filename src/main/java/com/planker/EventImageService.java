package com.planker;

import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Skill;

public class EventImageService
{
    private final EventBadgeRenderer badgeRenderer;
    private final ScreenshotCaptureService screenshotCaptureService;

    @Inject
    public EventImageService(EventBadgeRenderer badgeRenderer, ScreenshotCaptureService screenshotCaptureService)
    {
        this.badgeRenderer = badgeRenderer;
        this.screenshotCaptureService = screenshotCaptureService;
    }

    public void addSkillBadge(Map<String, Object> eventPayload, Skill skill, int level)
    {
        badgeRenderer.addSkillBadge(eventPayload, skill, level);
    }

    public void addBadge(Map<String, Object> eventPayload, String label, String subtitle)
    {
        badgeRenderer.addEventBadge(eventPayload, label, subtitle);
    }

    public void addScreenshotAsync(Map<String, Object> eventPayload, Runnable completionAction)
    {
        screenshotCaptureService.addScreenshotAsync(eventPayload, completionAction);
    }
}
