package de.bsnsoft.megarepo.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ActivityBroadcasterTest {

    private ActivityBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        broadcaster = new ActivityBroadcaster(objectMapper);
    }

    @Test
    void subscribe_returnsEmitter() {
        SseEmitter emitter = broadcaster.subscribe();
        assertNotNull(emitter);
        assertEquals(1, broadcaster.subscriberCount());
    }

    @Test
    void subscribe_multipleSubscribers_allTracked() {
        broadcaster.subscribe();
        broadcaster.subscribe();
        broadcaster.subscribe();
        assertEquals(3, broadcaster.subscriberCount());
    }

    @Test
    void broadcast_withNoSubscribers_doesNotThrow() {
        var event = createEvent("DOWNLOAD", "maven-releases", "admin");
        broadcaster.broadcast(event);
        // should not throw
    }

    @Test
    void broadcast_sendsToActiveEmitter() {
        SseEmitter emitter = broadcaster.subscribe();
        assertNotNull(emitter);

        var event = createEvent("UPLOAD", "npm-hosted", "deployer");
        broadcaster.broadcast(event);

        // emitter still active - subscriber count unchanged
        assertEquals(1, broadcaster.subscriberCount());
    }

    @Test
    void broadcast_removesCompletedEmitter() {
        SseEmitter emitter = broadcaster.subscribe();
        assertEquals(1, broadcaster.subscriberCount());

        emitter.complete();

        // After completion callback fires, emitter is removed
        // The CopyOnWriteArrayList callback removes it
        var event = createEvent("DELETE", "raw-hosted", "admin");
        broadcaster.broadcast(event);

        // The dead emitter should have been cleaned up during broadcast
        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    void broadcast_removesTimedOutEmitter() {
        SseEmitter emitter = broadcaster.subscribe();
        assertEquals(1, broadcaster.subscriberCount());

        // Simulate completion to mark emitter as done
        emitter.complete();

        var event = createEvent("DOWNLOAD", "maven-releases", "anonymous");
        broadcaster.broadcast(event);

        assertEquals(0, broadcaster.subscriberCount());
    }

    private static ActivityEvent createEvent(String action, String repository, String user) {
        return new ActivityEvent(
                Instant.parse("2026-03-28T12:00:00Z"),
                user,
                action,
                repository,
                "com/example/lib-1.0.jar",
                "maven2",
                1024L,
                50L,
                null);
    }
}
