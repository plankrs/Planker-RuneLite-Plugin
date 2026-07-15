package com.planker;

import com.planker.model.PlankerEvent;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.client.util.Text;

@Slf4j
public class PlayerEventService
{
    private static final long LOGIN_LEVEL_SUPPRESSION_MS = 10_000L;
    private static final long CHAT_DEDUP_RETENTION_MS = 10_000L;
    private static final long CHAT_DUPLICATE_WINDOW_MS = 3_000L;
    private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile(
        "(?i)new item added to your collection log:?\\s*(.+)");
    private static final Pattern QUEST_COMPLETION_PATTERN = Pattern.compile(
        "(?i)(?:congratulations[,]*)?\\s*you(?:'ve| have) completed (?:the quest[: ]+)?(.+?)[.!]?$|quest completed[: ]+(.+)");
    private static final Pattern COMBAT_ACHIEVEMENT_PATTERN = Pattern.compile(
        "(?i)(?:combat (?:task|achievement) completed)[: ]+(.+)");
    private static final Pattern COMBAT_TIER_SUFFIX_PATTERN = Pattern.compile(
        "^(.*?)(?:\\s*\\((Easy|Medium|Hard|Elite|Master|Grandmaster)\\))?$",
        Pattern.CASE_INSENSITIVE);

    private final Client client;
    private final PlankerConfig config;
    private final EventQueue eventQueue;
    private final EventImageService imageService;
    private final NotificationPolicyService policyService;
    private final Map<Skill, Integer> lastKnownXpBySkill = new EnumMap<>(Skill.class);
    private final Map<String, Long> recentSystemMessages = new LinkedHashMap<>();
    private long suppressLevelEventsUntil;

    @Inject
    public PlayerEventService(Client client, PlankerConfig config, EventQueue eventQueue,
        EventImageService imageService, NotificationPolicyService policyService)
    {
        this.client = client;
        this.config = config;
        this.eventQueue = eventQueue;
        this.imageService = imageService;
        this.policyService = policyService;
    }

    public void resetForLogin()
    {
        lastKnownXpBySkill.clear();
        recentSystemMessages.clear();
        suppressLevelEventsUntil = System.currentTimeMillis() + LOGIN_LEVEL_SUPPRESSION_MS;
    }

    public void onStatChanged(String loggedInRsn, StatChanged statChangedEvent)
    {
        if (!config.enableLevelReporting() || isBlank(loggedInRsn) || statChangedEvent == null)
        {
            return;
        }

        Skill changedSkill = statChangedEvent.getSkill();
        if (changedSkill == null || changedSkill == Skill.OVERALL)
        {
            return;
        }

        int currentXp = Math.max(0, statChangedEvent.getXp());
        Integer previousXp = lastKnownXpBySkill.put(changedSkill, currentXp);
        if (System.currentTimeMillis() < suppressLevelEventsUntil || previousXp == null || currentXp <= previousXp)
        {
            return;
        }

        int previousLevel = net.runelite.api.Experience.getLevelForXp(previousXp);
        int currentLevel = net.runelite.api.Experience.getLevelForXp(currentXp);
        if (currentLevel <= previousLevel || !policyService.shouldBroadcastLevel(currentLevel))
        {
            return;
        }

        Map<String, Object> levelPayload = new LinkedHashMap<>();
        levelPayload.put("skill", formatSkillName(changedSkill));
        levelPayload.put("level", currentLevel);
        imageService.addSkillBadge(levelPayload, changedSkill, currentLevel);
        enqueueWithOptionalScreenshot(
            loggedInRsn,
            "LEVEL_UP",
            levelPayload,
            config.attachScreenshots()
                && config.screenshotLevelUps()
                && policyService.screenshotLevelUps());
    }

    public void onChatMessage(String loggedInRsn, ChatMessage chatMessageEvent)
    {
        if (isBlank(loggedInRsn) || chatMessageEvent == null
            || chatMessageEvent.getType() != ChatMessageType.GAMEMESSAGE
            || chatMessageEvent.getMessage() == null)
        {
            return;
        }

        String systemMessage = Text.removeTags(chatMessageEvent.getMessage()).trim();
        if (systemMessage.isEmpty() || isDuplicateSystemMessage(systemMessage))
        {
            return;
        }

        Matcher collectionLogMatcher = COLLECTION_LOG_PATTERN.matcher(systemMessage);
        if (config.enableCollectionLogReporting() && collectionLogMatcher.find())
        {
            String unlockedItemName = collectionLogMatcher.group(1).trim();
            if (!unlockedItemName.isEmpty())
            {
                Map<String, Object> collectionLogPayload = new LinkedHashMap<>();
                collectionLogPayload.put("itemName", unlockedItemName);
                imageService.addBadge(collectionLogPayload, "LOG", "Collection Log");
                enqueueWithOptionalScreenshot(
                    loggedInRsn,
                    "COLLECTION_LOG",
                    collectionLogPayload,
                    config.attachScreenshots()
                        && config.screenshotCollectionLogs()
                        && policyService.screenshotCollectionLogs());
            }
            return;
        }

        Matcher questMatcher = QUEST_COMPLETION_PATTERN.matcher(systemMessage);
        if (config.enableQuestReporting() && questMatcher.find())
        {
            String completedQuestName = questMatcher.group(1) != null
                ? questMatcher.group(1).trim()
                : questMatcher.group(2) == null ? "" : questMatcher.group(2).trim();
            if (!completedQuestName.isEmpty())
            {
                Map<String, Object> questPayload = new LinkedHashMap<>();
                questPayload.put("questName", completedQuestName);
                imageService.addBadge(questPayload, "QUEST", "Completed");
                enqueueWithOptionalScreenshot(
                    loggedInRsn,
                    "QUEST_COMPLETED",
                    questPayload,
                    config.attachScreenshots()
                        && config.screenshotQuests()
                        && policyService.screenshotQuests());
            }
            return;
        }

        Matcher combatAchievementMatcher = COMBAT_ACHIEVEMENT_PATTERN.matcher(systemMessage);
        if (config.enableCombatAchievementReporting() && combatAchievementMatcher.find())
        {
            String rawAchievementName = combatAchievementMatcher.group(1).trim();
            Matcher tierMatcher = COMBAT_TIER_SUFFIX_PATTERN.matcher(rawAchievementName);
            if (!tierMatcher.matches())
            {
                return;
            }

            String achievementName = tierMatcher.group(1).trim();
            String achievementTier = tierMatcher.group(2);
            if (achievementName.isEmpty())
            {
                return;
            }

            Map<String, Object> combatAchievementPayload = new LinkedHashMap<>();
            combatAchievementPayload.put("achievementName", achievementName);
            if (achievementTier != null)
            {
                combatAchievementPayload.put("tier", achievementTier);
            }
            imageService.addBadge(
                combatAchievementPayload,
                "CA",
                achievementTier == null ? "Combat Task" : achievementTier);
            enqueueWithOptionalScreenshot(
                loggedInRsn,
                "COMBAT_ACHIEVEMENT",
                combatAchievementPayload,
                config.attachScreenshots()
                    && config.screenshotCombatAchievements()
                    && policyService.screenshotCombatAchievements());
        }
    }

    private void enqueueWithOptionalScreenshot(String loggedInRsn, String eventType,
        Map<String, Object> eventPayload, boolean includeScreenshot)
    {
        Runnable enqueueEvent = () -> enqueuePlayerEvent(loggedInRsn, eventType, eventPayload);
        if (includeScreenshot)
        {
            imageService.addScreenshotAsync(eventPayload, enqueueEvent);
        }
        else
        {
            enqueueEvent.run();
        }
    }

    private void enqueuePlayerEvent(String loggedInRsn, String eventType, Map<String, Object> eventPayload)
    {
        eventQueue.enqueue(PlankerEvent.builder()
            .eventType(eventType)
            .rsn(loggedInRsn)
            .data(eventPayload)
            .build());
        log.debug("Queued player event type={}", eventType);
    }

    private boolean isDuplicateSystemMessage(String systemMessage)
    {
        long currentTime = System.currentTimeMillis();
        recentSystemMessages.entrySet().removeIf(
            recordedMessage -> currentTime - recordedMessage.getValue() > CHAT_DEDUP_RETENTION_MS);
        String normalizedMessage = systemMessage.toLowerCase(Locale.ROOT);
        Long previouslySeenAt = recentSystemMessages.put(normalizedMessage, currentTime);
        return previouslySeenAt != null && currentTime - previouslySeenAt < CHAT_DUPLICATE_WINDOW_MS;
    }

    private String formatSkillName(Skill skill)
    {
        String normalizedSkillName = skill.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalizedSkillName.charAt(0)) + normalizedSkillName.substring(1);
    }

    private boolean isBlank(String text)
    {
        return text == null || text.trim().isEmpty();
    }
}
