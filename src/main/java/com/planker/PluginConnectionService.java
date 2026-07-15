package com.planker;

import com.planker.model.InstallationCheckIn;
import com.planker.model.InstallationCheckInResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;

@Slf4j
public class PluginConnectionService
{
    private final PlankerApiClient apiClient;
    private final EventQueue eventQueue;
    private final NotificationPolicyService policyService;
    private final Notifier notifier;
    private final Client client;
    private final ClientThread clientThread;
    private final AtomicBoolean checkInInProgress = new AtomicBoolean();
    private volatile String lastConnectionError;
    private volatile boolean updateNoticeShown;
    private volatile boolean requiredUpdateNoticeShown;

    @Inject
    public PluginConnectionService(
        PlankerApiClient apiClient,
        EventQueue eventQueue,
        NotificationPolicyService policyService,
        Notifier notifier,
        Client client,
        ClientThread clientThread)
    {
        this.apiClient = apiClient;
        this.eventQueue = eventQueue;
        this.policyService = policyService;
        this.notifier = notifier;
        this.client = client;
        this.clientThread = clientThread;
    }

    public void testConnection(String loggedInRsn)
    {
        showStatusMessage("Testing PlankRS connection...");
        checkIn(loggedInRsn, true);
    }

    public void showStatusMessage(String message)
    {
        if (message == null || message.trim().isEmpty())
        {
            return;
        }

        String statusMessage = message.trim();
        clientThread.invokeLater(() -> client.addChatMessage(
            ChatMessageType.CONSOLE,
            "PlankRS",
            statusMessage,
            null
        ));
    }

    public void checkIn(String loggedInRsn, boolean notifyUser)
    {
        if (loggedInRsn == null || loggedInRsn.trim().isEmpty())
        {
            handleConnectionFailure(
                "Log in to RuneScape before testing the PlankRS connection.", notifyUser);
            return;
        }

        if (!checkInInProgress.compareAndSet(false, true))
        {
            log.debug("Skipped overlapping PlankRS check-in");
            return;
        }

        InstallationCheckIn checkInPayload = InstallationCheckIn.builder()
            .pluginVersion(PluginVersion.VERSION)
            .policyVersion(policyService.policyVersion())
            .queuedEventCount(eventQueue.size())
            .rsn(loggedInRsn.trim())
            .lastError(lastConnectionError)
            .build();

        apiClient.checkIn(checkInPayload, new PlankerApiClient.CheckInCallback()
        {
            @Override
            public void onSuccess(InstallationCheckInResponse checkInResponse)
            {
                checkInInProgress.set(false);
                lastConnectionError = null;

                if (checkInResponse.isUpdateRequired())
                {
                    eventQueue.pauseForRequiredUpdate();
                    String requiredUpdateMessage = "PlankRS plugin " + PluginVersion.VERSION
                        + " is no longer supported. Update to "
                        + safeVersion(checkInResponse.getLatestPluginVersion()) + ".";
                    lastConnectionError = requiredUpdateMessage;
                    log.warn(requiredUpdateMessage);
                    if (!requiredUpdateNoticeShown)
                    {
                        notifier.notify(requiredUpdateMessage);
                        requiredUpdateNoticeShown = true;
                    }
                    if (notifyUser)
                    {
                        showStatusMessage(requiredUpdateMessage);
                    }
                    return;
                }

                requiredUpdateNoticeShown = false;
                eventQueue.resumeAfterConfigurationChange();
                policyService.refresh();
                log.info("Connected to PlankRS using plugin version {} and policy version {}",
                    PluginVersion.VERSION, checkInResponse.getPolicyVersion());

                if (checkInResponse.isUpdateAvailable() && !updateNoticeShown)
                {
                    notifier.notify("A newer PlankRS plugin is available: "
                        + safeVersion(checkInResponse.getLatestPluginVersion())
                        + " (current " + PluginVersion.VERSION + ").");
                    updateNoticeShown = true;
                    if (notifyUser)
                    {
                        showStatusMessage("Connected to PlankRS. A plugin update is available.");
                    }
                }
                else if (!checkInResponse.isUpdateAvailable())
                {
                    updateNoticeShown = false;
                    if (notifyUser)
                    {
                        notifier.notify("PlankRS connected successfully.");
                        showStatusMessage("Connected to PlankRS.");
                    }
                }
                else if (notifyUser)
                {
                    notifier.notify("PlankRS connected successfully; an update is available.");
                    showStatusMessage("Connected to PlankRS. A plugin update is available.");
                }
            }

            @Override
            public void onFailure(String failureMessage)
            {
                checkInInProgress.set(false);
                handleConnectionFailure(failureMessage, notifyUser);
            }
        });
    }

    private void handleConnectionFailure(String failureMessage, boolean notifyUser)
    {
        String safeFailureMessage = failureMessage == null || failureMessage.trim().isEmpty()
            ? "Connection check failed."
            : failureMessage.trim();
        lastConnectionError = safeFailureMessage;
        log.warn("PlankRS connection check failed: {}", safeFailureMessage);
        if (notifyUser)
        {
            notifier.notify("PlankRS connection failed: " + safeFailureMessage);
            showStatusMessage("Connection failed: " + safeFailureMessage);
        }
    }

    private String safeVersion(String version)
    {
        return version == null || version.trim().isEmpty() ? "the latest version" : version.trim();
    }
}
