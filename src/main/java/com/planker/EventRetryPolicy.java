package com.planker;

import java.util.concurrent.ThreadLocalRandom;

public class EventRetryPolicy
{
    private static final long MAX_BACKOFF_MS = 15 * 60 * 1000L;
    private static final long[] RETRY_SCHEDULE_MS = {5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L};

    public long calculateDelayMillis(int attemptCount, long serverRetryAfterMillis)
    {
        if (serverRetryAfterMillis > 0) return Math.min(MAX_BACKOFF_MS, serverRetryAfterMillis);
        int index = Math.min(Math.max(0, attemptCount - 1), RETRY_SCHEDULE_MS.length - 1);
        long baseDelay = RETRY_SCHEDULE_MS[index];
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseDelay / 5L));
        return Math.min(MAX_BACKOFF_MS, baseDelay + jitter);
    }
}
