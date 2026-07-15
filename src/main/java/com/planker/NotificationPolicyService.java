package com.planker;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.planker.model.NotificationPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

@Slf4j
public class NotificationPolicyService
{
    private final PlankerApiClient apiClient;
    private final Gson gson;
    private final Path policyCacheFile;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private volatile NotificationPolicy activePolicy = new NotificationPolicy();

    @Inject
    public NotificationPolicyService(PlankerApiClient apiClient, Gson gson)
    {
        this.apiClient = apiClient;
        this.gson = gson;
        this.policyCacheFile = RuneLite.RUNELITE_DIR.toPath()
            .resolve("planker")
            .resolve("policy-cache.json");
        loadCachedPolicy();
    }

    public void refresh()
    {
        if (!refreshInProgress.compareAndSet(false, true))
        {
            log.debug("Skipped overlapping PlankRS policy refresh");
            return;
        }

        apiClient.fetchPolicy(new PlankerApiClient.PolicyCallback()
        {
            @Override
            public void onSuccess(NotificationPolicy receivedPolicy)
            {
                refreshInProgress.set(false);
                if (!isValid(receivedPolicy))
                {
                    log.warn("Ignoring incomplete PlankRS notification policy response");
                    return;
                }

                activePolicy = receivedPolicy;
                persistPolicy(receivedPolicy);
                log.info(
                    "Loaded PlankRS policy v{}: drops >= {} gp, screenshots >= {} gp, "
                        + "level schedule every 5 through {}, every level from {}",
                    receivedPolicy.getVersion(),
                    receivedPolicy.getDrops().getMinimumValueGp(),
                    receivedPolicy.getScreenshots().getDropMinimumValueGp(),
                    receivedPolicy.getLevels().getEveryFiveThrough(),
                    receivedPolicy.getLevels().getEveryLevelFrom());
            }

            @Override
            public void onFailure(String failureMessage)
            {
                refreshInProgress.set(false);
                log.warn("Unable to refresh PlankRS notification policy; retaining current policy: {}",
                    failureMessage == null ? "request failed" : failureMessage);
            }
        });
    }

    private void loadCachedPolicy()
    {
        try
        {
            if (!Files.exists(policyCacheFile) || Files.size(policyCacheFile) == 0L)
            {
                return;
            }

            String serializedCache = new String(
                Files.readAllBytes(policyCacheFile), StandardCharsets.UTF_8);
            CachedPolicy cachedPolicy = gson.fromJson(serializedCache, CachedPolicy.class);
            if (cachedPolicy != null && isValid(cachedPolicy.getPolicy()))
            {
                activePolicy = cachedPolicy.getPolicy();
                log.info("Loaded cached PlankRS policy v{} retrieved at {}: drops >= {} gp",
                    activePolicy.getVersion(),
                    cachedPolicy.getRetrievedAt(),
                    activePolicy.getDrops().getMinimumValueGp());
                return;
            }

            log.warn("Cached PlankRS policy is incomplete; removing it");
            deleteInvalidCache();
        }
        catch (IOException | JsonParseException exception)
        {
            log.warn("Unable to load the cached PlankRS policy; using defaults");
            log.debug("Cached policy load failure", exception);
            deleteInvalidCache();
        }
    }

    private void deleteInvalidCache()
    {
        try
        {
            Files.deleteIfExists(policyCacheFile);
        }
        catch (IOException deletionFailure)
        {
            log.warn("Unable to remove the invalid policy cache: {}", deletionFailure.getMessage());
            log.debug("Policy cache deletion failure", deletionFailure);
        }
    }

    private void persistPolicy(NotificationPolicy notificationPolicy)
    {
        try
        {
            Files.createDirectories(policyCacheFile.getParent());
            CachedPolicy cachedPolicy = new CachedPolicy();
            cachedPolicy.setRetrievedAt(Instant.now().toString());
            cachedPolicy.setPolicy(notificationPolicy);

            Path temporaryPolicyFile = policyCacheFile.resolveSibling(
                policyCacheFile.getFileName() + ".tmp");
            Files.write(
                temporaryPolicyFile,
                gson.toJson(cachedPolicy).getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(
                    temporaryPolicyFile,
                    policyCacheFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicMoveFailure)
            {
                log.debug("Atomic policy-cache replacement unavailable; using standard replacement");
                Files.move(
                    temporaryPolicyFile,
                    policyCacheFile,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException persistenceFailure)
        {
            log.warn("Unable to persist the PlankRS policy cache: {}", persistenceFailure.getMessage());
            log.debug("Policy cache persistence failure", persistenceFailure);
        }
    }

    private boolean isValid(NotificationPolicy notificationPolicy)
    {
        if (notificationPolicy == null
            || notificationPolicy.getDrops() == null
            || notificationPolicy.getLevels() == null
            || notificationPolicy.getScreenshots() == null)
        {
            return false;
        }

        NotificationPolicy.Levels levelPolicy = notificationPolicy.getLevels();
        return levelPolicy.getEveryFiveThrough() >= 0
            && levelPolicy.getEveryLevelFrom() >= 1
            && levelPolicy.getMaximumVirtualLevel() >= levelPolicy.getEveryLevelFrom();
    }

    public long minimumDropValueGp()
    {
        return Math.max(0L, activePolicy.getDrops().getMinimumValueGp());
    }

    public long minimumDropScreenshotValueGp()
    {
        return Math.max(0L, activePolicy.getScreenshots().getDropMinimumValueGp());
    }

    public boolean screenshotsEnabled()
    {
        return activePolicy.getScreenshots().isEnabled();
    }

    public boolean screenshotLevelUps()
    {
        return screenshotsEnabled() && activePolicy.getScreenshots().isLevelUps();
    }

    public boolean screenshotQuests()
    {
        return screenshotsEnabled() && activePolicy.getScreenshots().isQuests();
    }

    public boolean screenshotCollectionLogs()
    {
        return screenshotsEnabled() && activePolicy.getScreenshots().isCollectionLogs();
    }

    public boolean screenshotCombatAchievements()
    {
        return screenshotsEnabled() && activePolicy.getScreenshots().isCombatAchievements();
    }

    public boolean shouldBroadcastLevel(int level)
    {
        NotificationPolicy.Levels levelPolicy = activePolicy.getLevels();
        if (level < 5 || level > levelPolicy.getMaximumVirtualLevel())
        {
            return false;
        }
        if (level <= levelPolicy.getEveryFiveThrough())
        {
            return level % 5 == 0;
        }
        return level >= levelPolicy.getEveryLevelFrom();
    }

    public int policyVersion()
    {
        return Math.max(0, activePolicy.getVersion());
    }

    @Data
    private static class CachedPolicy
    {
        private String retrievedAt;
        private NotificationPolicy policy;
    }
}
