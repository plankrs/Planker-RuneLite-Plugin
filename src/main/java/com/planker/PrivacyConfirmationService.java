package com.planker;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;

@Singleton
public class PrivacyConfirmationService
{
    private static final String CONFIG_GROUP = "planker";
    private static final String CLAN_CHAT_SETTING = "enableClanChatRelay";
    private static final String SCREENSHOT_SETTING = "attachScreenshots";

    private final ConfigManager configManager;
    private final PluginConnectionService connectionService;
    private final AtomicBoolean clanChatDialogOpen = new AtomicBoolean();
    private final AtomicBoolean screenshotDialogOpen = new AtomicBoolean();

    private volatile boolean applyingConfirmedClanChatSetting;
    private volatile boolean applyingConfirmedScreenshotSetting;

    @Inject
    public PrivacyConfirmationService(
        ConfigManager configManager,
        PluginConnectionService connectionService)
    {
        this.configManager = configManager;
        this.connectionService = connectionService;
    }

    public boolean consumeConfirmedClanChatChange()
    {
        if (!applyingConfirmedClanChatSetting)
        {
            return false;
        }

        applyingConfirmedClanChatSetting = false;
        return true;
    }

    public boolean consumeConfirmedScreenshotChange()
    {
        if (!applyingConfirmedScreenshotSetting)
        {
            return false;
        }

        applyingConfirmedScreenshotSetting = false;
        return true;
    }

    public void requestClanChatConfirmation()
    {
        configManager.setConfiguration(CONFIG_GROUP, CLAN_CHAT_SETTING, false);
        if (!clanChatDialogOpen.compareAndSet(false, true))
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            try
            {
                int choice = JOptionPane.showConfirmDialog(
                    null,
                    "Clan chat relay forwards messages visible in your RuneLite clan chat.\n\n"
                        + "This may include messages written by other clan members who do not use PlankRS. "
                        + "Sender names and message text will be sent to the PlankRS backend and posted in the clan Discord.\n\n"
                        + "Enable clan chat relay?",
                    "Enable PlankRS clan chat relay",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );

                if (choice == JOptionPane.YES_OPTION)
                {
                    applyingConfirmedClanChatSetting = true;
                    configManager.setConfiguration(CONFIG_GROUP, CLAN_CHAT_SETTING, true);
                    connectionService.showStatusMessage("Clan chat relay enabled.");
                }
                else
                {
                    connectionService.showStatusMessage("Clan chat relay remains disabled.");
                }
            }
            finally
            {
                clanChatDialogOpen.set(false);
            }
        });
    }

    public void requestScreenshotConfirmation()
    {
        configManager.setConfiguration(CONFIG_GROUP, SCREENSHOT_SETTING, false);
        if (!screenshotDialogOpen.compareAndSet(false, true))
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            try
            {
                int choice = JOptionPane.showConfirmDialog(
                    null,
                    "Screenshots are captured only for supported PlankRS events.\n\n"
                        + "They may include clan chat, overlays, nearby player names, and other "
                        + "content visible in RuneLite at the time of capture.\n\n"
                        + "The image will be sent to the PlankRS backend and posted in the clan Discord.\n\n"
                        + "Enable screenshot attachments?",
                    "Enable PlankRS screenshots",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );

                if (choice == JOptionPane.YES_OPTION)
                {
                    applyingConfirmedScreenshotSetting = true;
                    configManager.setConfiguration(CONFIG_GROUP, SCREENSHOT_SETTING, true);
                    connectionService.showStatusMessage("Screenshot attachments enabled.");
                }
                else
                {
                    connectionService.showStatusMessage("Screenshot attachments remain disabled.");
                }
            }
            finally
            {
                screenshotDialogOpen.set(false);
            }
        });
    }
}
