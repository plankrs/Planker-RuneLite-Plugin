package com.planker;

import com.planker.model.PlankerEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.util.Text;

@Slf4j
public class ClanChatRelayService
{
    private static final int MAX_MESSAGE_CODE_POINTS = 500;
    private static final Pattern DISCORD_MASS_MENTION_PATTERN = Pattern.compile("(?i)@(everyone|here)");
    private static final Pattern DISCORD_USER_OR_ROLE_MENTION_PATTERN = Pattern.compile("<@([!&]?)\\d+>");

    private final PlankerConfig config;
    private final EventQueue eventQueue;

    @Inject
    public ClanChatRelayService(PlankerConfig config, EventQueue eventQueue)
    {
        this.config = config;
        this.eventQueue = eventQueue;
    }

    public void onChatMessage(String loggedInRsn, ChatMessage chatMessageEvent)
    {
        if (!config.enableClanChatRelay() || isBlank(loggedInRsn) || chatMessageEvent == null)
        {
            return;
        }

        ChatMessageType chatMessageType = chatMessageEvent.getType();
        boolean receivedClanChat = chatMessageType == ChatMessageType.CLAN_CHAT;
        boolean locallySentClanChat = chatMessageType == ChatMessageType.CLAN_MESSAGE;
        if (!receivedClanChat && !locallySentClanChat)
        {
            return;
        }

        String senderDisplayName = sanitizeChatText(chatMessageEvent.getName());
        String clanMessageText = sanitizeChatText(chatMessageEvent.getMessage());
        if (senderDisplayName.isEmpty() || clanMessageText.isEmpty())
        {
            return;
        }

        if (clanMessageText.startsWith("!") || clanMessageText.startsWith("/"))
        {
            return;
        }

        String discordSafeMessage = neutralizeDiscordMentions(clanMessageText);
        String boundedMessage = truncateByCodePoints(discordSafeMessage, MAX_MESSAGE_CODE_POINTS);

        Map<String, Object> clanChatPayload = new LinkedHashMap<>();
        clanChatPayload.put("sender", senderDisplayName);
        clanChatPayload.put("message", boundedMessage);

        eventQueue.enqueue(PlankerEvent.builder()
            .eventType("CLAN_CHAT_MESSAGE")
            .rsn(loggedInRsn.trim())
            .data(clanChatPayload)
            .build());

        log.debug("Queued clan-chat relay event");
    }

    private static String sanitizeChatText(String rawText)
    {
        if (rawText == null)
        {
            return "";
        }
        return Text.removeTags(rawText)
            .replace('\u00A0', ' ')
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static String neutralizeDiscordMentions(String clanMessageText)
    {
        String withoutMassMentions = DISCORD_MASS_MENTION_PATTERN
            .matcher(clanMessageText)
            .replaceAll("@\\u200B$1");
        return DISCORD_USER_OR_ROLE_MENTION_PATTERN
            .matcher(withoutMassMentions)
            .replaceAll("@\\u200B$1user");
    }

    private static String truncateByCodePoints(String text, int maximumCodePoints)
    {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maximumCodePoints)
        {
            return text;
        }

        int endIndex = text.offsetByCodePoints(0, Math.max(0, maximumCodePoints - 1));
        return text.substring(0, endIndex) + "…";
    }

    private static boolean isBlank(String text)
    {
        return text == null || text.trim().isEmpty();
    }
}
