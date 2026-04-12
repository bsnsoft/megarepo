package de.bsnsoft.megarepo.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ActivityBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ActivityBroadcaster.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public ActivityBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(ActivityEvent event) {
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                String json = objectMapper.writeValueAsString(event);
                emitter.send(SseEmitter.event().name("activity").data(json));
            } catch (IOException | IllegalStateException e) {
                deadEmitters.add(emitter);
                log.debug("Removing dead SSE emitter: {}", e.getMessage());
            }
        }
        emitters.removeAll(deadEmitters);
    }

    int subscriberCount() {
        return emitters.size();
    }
}
