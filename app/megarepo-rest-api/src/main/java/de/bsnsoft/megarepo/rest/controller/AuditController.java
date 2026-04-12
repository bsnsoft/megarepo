package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.entity.AuditLogEntity;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.rest.dto.audit.AuditLogXO;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int PAGE_SIZE = 50;
    private static final int EXPORT_MAX_ROWS = 10_000;

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditController(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<PageResponse<AuditLogXO>> list(
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String continuationToken) {

        int pageNumber = decodePage(continuationToken);
        var pageable = PageRequest.of(pageNumber, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "timestamp"));

        Page<AuditLogEntity> pageResult = queryAuditLog(repository, user, from, to, action, pageable);

        var items = pageResult.getContent().stream()
                .map(AuditController::toXO)
                .toList();

        String nextToken = pageResult.hasNext() ? encodePage(pageNumber + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, nextToken));
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String action,
            HttpServletResponse response)
            throws IOException {

        var pageable = PageRequest.of(0, EXPORT_MAX_ROWS, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLogEntity> pageResult = queryAuditLog(repository, user, from, to, action, pageable);
        List<AuditLogXO> items = pageResult.getContent().stream()
                .map(AuditController::toXO)
                .toList();

        if ("json".equalsIgnoreCase(format)) {
            response.setContentType("application/json");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.json\"");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(response.getOutputStream(), items);
        } else {
            response.setContentType("text/csv");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"");
            writeCsv(response.getWriter(), items);
        }
    }

    private void writeCsv(PrintWriter writer, List<AuditLogXO> items) {
        writer.println("id,timestamp,userId,action,repository,path,sourceUrl,size,ipAddress,format,durationMs");
        for (AuditLogXO item : items) {
            writer.printf(
                    "%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    item.id(),
                    item.timestamp(),
                    escapeCsv(item.userId()),
                    escapeCsv(item.action()),
                    escapeCsv(item.repository()),
                    escapeCsv(item.path()),
                    escapeCsv(item.sourceUrl()),
                    item.size() != null ? item.size() : "",
                    escapeCsv(item.ipAddress()),
                    escapeCsv(item.format()),
                    item.durationMs() != null ? item.durationMs() : "");
        }
        writer.flush();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Page<AuditLogEntity> queryAuditLog(
            String repository, String user, Instant from, Instant to, String action, PageRequest pageable) {
        // Priority: most specific filter first
        if (repository != null && from != null && to != null) {
            return auditService.findByRepositoryAndTimeRange(repository, from, to, pageable);
        }
        if (repository != null && action != null) {
            return auditService.findByRepositoryAndAction(repository, action, pageable);
        }
        if (repository != null) {
            return auditService.findByRepository(repository, pageable);
        }
        if (user != null) {
            return auditService.findByUser(user, pageable);
        }
        if (from != null && to != null) {
            return auditService.findByTimeRange(from, to, pageable);
        }
        if (action != null) {
            return auditService.findByAction(action, pageable);
        }
        return auditService.findAll(pageable);
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

    private int decodePage(String continuationToken) {
        if (continuationToken == null || continuationToken.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(continuationToken)));
        } catch (Exception e) {
            return 0;
        }
    }

    private String encodePage(int page) {
        return Base64.getEncoder().encodeToString(String.valueOf(page).getBytes());
    }
}
