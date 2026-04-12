package de.bsnsoft.megarepo.app.migration;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/migrate/nexus")
public class MigrationController {

    private final NexusMigrationService migrationService;

    public MigrationController(NexusMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/preview")
    public ResponseEntity<NexusMigrationPreview> preview(
            @Valid @RequestBody NexusMigrationRequest request) {
        var preview = migrationService.preview(request);
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/execute")
    public ResponseEntity<NexusMigrationResult> execute(
            @Valid @RequestBody NexusMigrationRequest request) {
        var result = migrationService.execute(request);
        return ResponseEntity.ok(result);
    }
}
