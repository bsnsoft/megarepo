package de.bsnsoft.megarepo.tasks.nvd;

import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.repository.nvd.NvdSyncService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scheduled daily delta sync for the NVD mirror. Registers as task type
 * "security.nvd.sync"; a scheduled_tasks row with a cron expression drives it.
 */
@Component
public class NvdSyncTask {

    public static final String TASK_TYPE = "security.nvd.sync";

    private static final Logger log = LoggerFactory.getLogger(NvdSyncTask.class);

    private final NvdSyncService syncService;
    private final NvdFirewallSettingsJpaRepository settingsRepo;
    private final TaskRunner taskRunner;

    public NvdSyncTask(
            NvdSyncService syncService,
            NvdFirewallSettingsJpaRepository settingsRepo,
            TaskRunner taskRunner) {
        this.syncService = syncService;
        this.settingsRepo = settingsRepo;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    public void execute() {
        NvdFirewallSettingsEntity settings = settingsRepo.findById(1)
                .orElseGet(NvdFirewallSettingsEntity::new);
        if (!settings.isEnabled() || settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            log.info("NVD firewall disabled or API key not set — skipping scheduled sync");
            return;
        }
        boolean started = syncService.triggerDeltaSync();
        if (!started) {
            log.info("Skipping scheduled NVD sync — another sync is already in progress");
        } else {
            log.info("Scheduled NVD delta sync started");
        }
    }
}
