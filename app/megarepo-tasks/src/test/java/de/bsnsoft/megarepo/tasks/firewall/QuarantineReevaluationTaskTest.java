package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineProperties;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The scheduled sweep. Three things matter: it registers under the type the V19
 * row names, it honours the batch size, and switching quarantine off switches it
 * off too.
 */
@ExtendWith(MockitoExtension.class)
class QuarantineReevaluationTaskTest {

    @Mock private QuarantineService quarantine;
    @Mock private TaskRunner taskRunner;

    @Test
    @DisplayName("the task type is the one V19 seeds")
    void taskTypeMatchesTheMigration() {
        assertThat(QuarantineReevaluationTask.TASK_TYPE)
                .isEqualTo("security.firewall.quarantine.reevaluate");
    }

    @Test
    @DisplayName("registration does not sweep")
    void registrationDoesNotSweep() {
        task(QuarantineProperties.defaults()).register();

        verify(taskRunner).registerHandler(
                eq(QuarantineReevaluationTask.TASK_TYPE), any(Runnable.class));
        verifyNoInteractions(quarantine);
    }

    @Test
    @DisplayName("one tick sweeps at most one batch")
    void sweepIsBounded() {
        QuarantineProperties properties = new QuarantineProperties(
                true, 25, Duration.ofMinutes(5), Duration.ofHours(6), true, Duration.ofDays(90));

        task(properties).execute();

        verify(quarantine).reevaluateDue(any(), eq(25));
    }

    @Test
    @DisplayName("quarantine switched off: the task does nothing at all")
    void disabledDoesNothing() {
        task(QuarantineProperties.disabled()).execute();

        verify(quarantine, org.mockito.Mockito.never()).reevaluateDue(any(), anyInt());
    }

    private QuarantineReevaluationTask task(QuarantineProperties properties) {
        return new QuarantineReevaluationTask(quarantine, properties, taskRunner);
    }
}
