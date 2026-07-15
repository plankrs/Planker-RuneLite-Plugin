package com.planker;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
    name = "PlankRS Clan",
    description = "Reports opted-in events for the logged-in account to the PlankRS clan Discord",
    tags = {"clan", "discord", "drops", "quests", "achievements", "chat"}
)
public class PlankerPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private PlankerConfig config;
    @Inject private ConfigManager configManager;
    @Inject private EventQueue eventQueue;
    @Inject private ScheduledExecutorService executor;
    @Inject private LootEventService lootEventService;
    @Inject private PlayerEventService playerEventService;
    @Inject private ClanChatRelayService clanChatRelayService;
    @Inject private NotificationPolicyService notificationPolicyService;
    @Inject private PluginConnectionService pluginConnectionService;
    @Inject private PrivacyConfirmationService privacyConfirmationService;

    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    @Provides
    PlankerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(PlankerConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("PlankRS Clan started");
        scheduledTasks.add(executor.scheduleWithFixedDelay(eventQueue::retryPending, 5, 5, TimeUnit.SECONDS));
        scheduledTasks.add(executor.scheduleWithFixedDelay(
            () -> pluginConnectionService.checkIn(resolveRsn(), false),
            5,
            5,
            TimeUnit.MINUTES
        ));
        scheduledTasks.add(executor.scheduleWithFixedDelay(
            notificationPolicyService::refresh,
            15,
            15,
            TimeUnit.MINUTES
        ));
    }

    @Override
    protected void shutDown()
    {
        for (ScheduledFuture<?> scheduledTask : scheduledTasks)
        {
            scheduledTask.cancel(false);
        }
        scheduledTasks.clear();
        log.info("PlankRS Clan stopped with {} queued event(s)", eventQueue.size());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            eventQueue.retryPending();
            playerEventService.resetForLogin();
            pluginConnectionService.checkIn(resolveRsn(), false);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"planker".equals(event.getGroup()))
        {
            return;
        }

        if ("testConnection".equals(event.getKey()) && config.testConnection())
        {
            pluginConnectionService.testConnection(resolveRsn());
            configManager.setConfiguration("planker", "testConnection", false);
            return;
        }

        if ("enableClanChatRelay".equals(event.getKey()) && config.enableClanChatRelay())
        {
            if (privacyConfirmationService.consumeConfirmedClanChatChange())
            {
                return;
            }

            privacyConfirmationService.requestClanChatConfirmation();
            return;
        }

        if ("attachScreenshots".equals(event.getKey()) && config.attachScreenshots())
        {
            if (privacyConfirmationService.consumeConfirmedScreenshotChange())
            {
                return;
            }

            privacyConfirmationService.requestScreenshotConfirmation();
            return;
        }

        if ("apiUrl".equals(event.getKey()) || "apiKey".equals(event.getKey()))
        {
            eventQueue.resumeAfterConfigurationChange();
            pluginConnectionService.checkIn(resolveRsn(), false);
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        playerEventService.onStatChanged(resolveRsn(), event);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        String loggedInRsn = resolveRsn();
        playerEventService.onChatMessage(loggedInRsn, event);
        clanChatRelayService.onChatMessage(loggedInRsn, event);
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        String loggedInRsn = resolveRsn();
        if (loggedInRsn == null)
        {
            return;
        }

        String npcName = event.getNpc() == null ? null : event.getNpc().getName();
        lootEventService.processNpcLoot(loggedInRsn, npcName, event.getItems());
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        String loggedInRsn = resolveRsn();
        if (loggedInRsn == null)
        {
            return;
        }

        String npcName = event.getComposition() == null ? null : event.getComposition().getName();
        lootEventService.processNpcLoot(loggedInRsn, npcName, event.getItems());
    }

    private String resolveRsn()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            return null;
        }

        return client.getLocalPlayer().getName();
    }
}
