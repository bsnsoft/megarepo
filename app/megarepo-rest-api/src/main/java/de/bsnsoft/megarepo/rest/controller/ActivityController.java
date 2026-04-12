package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import de.bsnsoft.megarepo.repository.ActivityBroadcaster;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.rest.dto.audit.AuditLogXO;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

    private static final int RECENT_EVENT_COUNT = 50;

    private final ActivityBroadcaster activityBroadcaster;
    private final AuditService auditService;

    public ActivityController(ActivityBroadcaster activityBroadcaster, AuditService auditService) {
        this.activityBroadcaster = activityBroadcaster;
        this.auditService = auditService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return activityBroadcaster.subscribe();
    }

    @GetMapping("/recent")
    public ResponseEntity<List<AuditLogXO>> recent() {
        var pageable = PageRequest.of(0, RECENT_EVENT_COUNT, Sort.by(Sort.Direction.DESC, "timestamp"));
        var page = auditService.findAll(pageable);
        var items = page.getContent().stream()
                .map(ActivityController::toXO)
                .toList();
        return ResponseEntity.ok(items);
    }

    private static AuditLogXO toXO(AuditLogEntity entity) {
        return new AuditLogXO(
                entity.getId(),
                entity.getTimestamp(),
                entity.getUserId(),
                entity.getAction(),
                entity.getRepository(),
                entity.getPath(),
                entity.getSourceUrl(),
                entity.getSize(),
                entity.getIpAddress(),
                entity.getFormat(),
                entity.getDurationMs());
    }
}
