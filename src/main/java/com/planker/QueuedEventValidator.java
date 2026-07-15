package com.planker;

import com.planker.model.PlankerEvent;
import com.planker.model.QueuedEvent;

public class QueuedEventValidator
{
    public boolean isValid(QueuedEvent queuedEvent) { return queuedEvent != null && isValid(queuedEvent.getEvent()); }
    public boolean isValid(PlankerEvent event) { return event != null && hasText(event.getEventId()) && hasText(event.getEventType()) && hasText(event.getRsn()); }
    public String eventId(QueuedEvent queuedEvent) { return isValid(queuedEvent) ? queuedEvent.getEvent().getEventId() : "unknown"; }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
