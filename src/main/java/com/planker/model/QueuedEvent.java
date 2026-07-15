package com.planker.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class QueuedEvent
{
    private PlankerEvent event;
    private int attemptCount;
    private long nextAttemptAtEpochMs;

    public QueuedEvent(PlankerEvent event)
    {
        this.event = event;
        this.attemptCount = 0;
        this.nextAttemptAtEpochMs = 0L;
    }
}
