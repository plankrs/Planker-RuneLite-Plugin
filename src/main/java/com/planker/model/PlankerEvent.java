package com.planker.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PlankerEvent
{
    @Builder.Default
    String eventId = UUID.randomUUID().toString();

    String eventType;
    String rsn;

    @Builder.Default
    String occurredAt = Instant.now().toString();

    @Builder.Default
    String source = "RUNELITE";

    Map<String, Object> data;
}
