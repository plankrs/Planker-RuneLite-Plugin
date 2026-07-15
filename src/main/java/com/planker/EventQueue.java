package com.planker;

import com.planker.model.PlankerEvent;
import com.planker.model.QueuedEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventQueue
{
    private static final int MAX_QUEUE_SIZE = 500;
    private static final int MAX_PERMANENT_FAILURES = 3;
    private static final long MAX_RESTORED_DELAY_MS = 24 * 60 * 60 * 1000L;
    private final Deque<QueuedEvent> pendingEvents = new ArrayDeque<>();
    private final PlankerApiClient apiClient;
    private final EventQueueStore queueStore;
    private final EventRetryPolicy retryPolicy;
    private final QueuedEventValidator validator;
    private boolean submissionInProgress;
    private boolean pausedForAuthentication;
    private boolean pausedForRequiredUpdate;

    @Inject
    public EventQueue(PlankerApiClient apiClient, EventQueueStore queueStore, EventRetryPolicy retryPolicy, QueuedEventValidator validator)
    {
        this.apiClient = apiClient;
        this.queueStore = queueStore;
        this.retryPolicy = retryPolicy;
        this.validator = validator;
        restorePersistedEvents();
    }

    public synchronized void enqueue(PlankerEvent gameEvent)
    {
        if (!validator.isValid(gameEvent)) { log.warn("Ignored an invalid PlankRS event before queueing"); return; }
        if (pendingEvents.size() >= MAX_QUEUE_SIZE) log.warn("PlankRS queue reached capacity; discarded oldest event {}", validator.eventId(pendingEvents.removeFirst()));
        pendingEvents.addLast(new QueuedEvent(gameEvent));
        saveQueue();
        submitNextEvent();
    }

    private synchronized void submitNextEvent()
    {
        if (submissionInProgress || pausedForAuthentication || pausedForRequiredUpdate || pendingEvents.isEmpty()) return;
        QueuedEvent queuedEvent = pendingEvents.peekFirst();
        if (!validator.isValid(queuedEvent)) { pendingEvents.removeFirst(); saveQueue(); submitNextEvent(); return; }
        if (queuedEvent.getNextAttemptAtEpochMs() > System.currentTimeMillis()) return;
        submissionInProgress = true;
        apiClient.send(queuedEvent.getEvent(), new PlankerApiClient.ResultCallback()
        {
            @Override public void onSuccess() { synchronized (EventQueue.this) { removeMatching(queuedEvent); submissionInProgress=false; saveQueue(); log.debug("Submitted PlankRS event {}",validator.eventId(queuedEvent)); submitNextEvent(); } }
            @Override public void onFailure(PlankerApiClient.ApiFailure failure) { synchronized (EventQueue.this) { handleFailure(queuedEvent,failure); } }
        });
    }

    private void handleFailure(QueuedEvent queuedEvent, PlankerApiClient.ApiFailure failure)
    {
        submissionInProgress = false;
        queuedEvent.setAttemptCount(Math.max(0, queuedEvent.getAttemptCount()) + 1);
        if (failure.isAuthenticationFailure()) { pausedForAuthentication=true; queuedEvent.setNextAttemptAtEpochMs(0L); saveQueue(); log.warn("PlankRS queue paused after an authentication failure"); return; }
        if (failure.isPermanentFailure() && queuedEvent.getAttemptCount() >= MAX_PERMANENT_FAILURES) { removeMatching(queuedEvent); saveQueue(); log.warn("Discarded event {} after {} permanent rejection(s): {}",validator.eventId(queuedEvent),queuedEvent.getAttemptCount(),failure.getMessage()); submitNextEvent(); return; }
        long delay=retryPolicy.calculateDelayMillis(queuedEvent.getAttemptCount(),failure.getRetryAfterMillis());
        queuedEvent.setNextAttemptAtEpochMs(System.currentTimeMillis()+delay);
        saveQueue();
        log.warn("Event {} remains queued; attempt {} will retry in {} second(s): {}",validator.eventId(queuedEvent),queuedEvent.getAttemptCount(),Math.max(1L,delay/1000L),failure.getMessage());
    }

    public synchronized void retryPending(){submitNextEvent();}
    public synchronized void resumeAfterConfigurationChange(){pausedForAuthentication=false;pausedForRequiredUpdate=false;for(QueuedEvent event:pendingEvents)event.setNextAttemptAtEpochMs(0L);saveQueue();submitNextEvent();}
    public synchronized void pauseForRequiredUpdate(){pausedForRequiredUpdate=true;}
    public synchronized int size(){return pendingEvents.size();}

    private void restorePersistedEvents()
    {
        long now=System.currentTimeMillis();
        for(QueuedEvent event:queueStore.load())
        {
            if(!validator.isValid(event))continue;
            event.setAttemptCount(Math.max(0,event.getAttemptCount()));
            if(event.getNextAttemptAtEpochMs()<0L||event.getNextAttemptAtEpochMs()>now+MAX_RESTORED_DELAY_MS)event.setNextAttemptAtEpochMs(0L);
            if(pendingEvents.size()==MAX_QUEUE_SIZE)pendingEvents.removeFirst();
            pendingEvents.addLast(event);
        }
        if(!pendingEvents.isEmpty())log.info("Loaded {} persisted PlankRS event(s)",pendingEvents.size());
    }
    private void saveQueue(){queueStore.save(pendingEvents);}
    private void removeMatching(QueuedEvent expected){if(pendingEvents.peekFirst()==expected)pendingEvents.removeFirst();else{pendingEvents.remove(expected);log.debug("Queue head changed before completion; removed matching queued event by identity");}}
}
