package com.planker;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.planker.model.QueuedEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

@Slf4j
public class EventQueueStore
{
    private final Gson gson;
    private final Path storageDirectory;
    private final Path queueFile;

    @Inject
    public EventQueueStore(Gson gson)
    {
        this.gson = gson;
        storageDirectory = RuneLite.RUNELITE_DIR.toPath().resolve("planker");
        queueFile = storageDirectory.resolve("event-queue.json");
    }

    public Collection<QueuedEvent> load()
    {
        try
        {
            Files.createDirectories(storageDirectory);
            if (!Files.exists(queueFile) || Files.size(queueFile) == 0L) return Collections.emptyList();
            QueuedEvent[] events = gson.fromJson(new String(Files.readAllBytes(queueFile), StandardCharsets.UTF_8), QueuedEvent[].class);
            if (events == null) return Collections.emptyList();
            ArrayList<QueuedEvent> result = new ArrayList<>();
            Collections.addAll(result, events);
            return result;
        }
        catch (IOException | JsonParseException exception)
        {
            log.warn("Unable to read the persisted PlankRS queue; removing the unreadable file");
            log.debug("Persisted queue read failure", exception);
            delete();
            return Collections.emptyList();
        }
    }

    public void save(Collection<QueuedEvent> events)
    {
        try
        {
            Files.createDirectories(storageDirectory);
            Path temporaryFile = queueFile.resolveSibling(queueFile.getFileName() + ".tmp");
            Files.write(temporaryFile, gson.toJson(new ArrayList<>(events)).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temporaryFile, queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException atomicMoveFailure) { log.debug("Atomic queue replacement unavailable; using standard replacement"); Files.move(temporaryFile, queueFile, StandardCopyOption.REPLACE_EXISTING); }
        }
        catch (IOException exception)
        {
            log.error("Unable to persist the PlankRS event queue: {}", exception.getMessage());
            log.debug("Event queue persistence failure", exception);
        }
    }

    private void delete()
    {
        try { Files.deleteIfExists(queueFile); }
        catch (IOException exception) { log.warn("Unable to remove the unreadable event queue: {}", exception.getMessage()); log.debug("Unreadable queue deletion failure", exception); }
    }
}
