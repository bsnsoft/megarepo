package de.bsnsoft.megarepo.tasks.advisory;

import de.bsnsoft.megarepo.repository.advisory.AdvisoryIngestService;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySource;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncSummary;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled advisory sync. What matters is that it registers without doing
 * anything, and that a partly failing run is not treated like a dead one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvisorySyncTaskTest {

    @Mock private AdvisoryIngestService ingestService;
    @Mock private TaskRunner taskRunner;

    @Test
    @DisplayName("registration does not start an import")
    void registrationDoesNotSync() {
        AdvisorySyncTask task = task(AdvisorySyncProperties.defaults(), source("OSV"));

        task.register();

        verify(taskRunner).registerHandler(eq(AdvisorySyncTask.TASK_TYPE), any(Runnable.class));
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("every configured source is synced")
    void syncsEverySource() {
        AdvisorySource osv = source("OSV");
        AdvisorySource ghsa = source("GHSA");
        when(ingestService.syncAll(any())).thenReturn(List.of(ok("OSV"), ok("GHSA")));
        AdvisorySyncTask task = task(AdvisorySyncProperties.defaults(), osv, ghsa);

        task.execute();

        verify(ingestService).syncAll(List.of(osv, ghsa));
    }

    @Test
    @DisplayName("the property switches the sync off without touching the sources")
    void disabledSkipsEverything() {
        AdvisorySyncTask task = task(new AdvisorySyncProperties(false), source("OSV"));

        task.execute();

        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("one failing source does not fail the task — the mirror still moved")
    void partialFailureIsNotATaskFailure() {
        when(ingestService.syncAll(any())).thenReturn(List.of(ok("OSV"), failed("GHSA")));
        AdvisorySyncTask task = task(AdvisorySyncProperties.defaults(), source("OSV"), source("GHSA"));

        assertThatCode(task::execute).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every source failing is a task failure, so the task list shows it")
    void totalFailureSurfaces() {
        when(ingestService.syncAll(any())).thenReturn(List.of(failed("OSV"), failed("GHSA")));
        AdvisorySyncTask task = task(AdvisorySyncProperties.defaults(), source("OSV"), source("GHSA"));

        assertThatThrownBy(task::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("advisory_sync_state");
    }

    @Test
    @DisplayName("no sources configured is a warning, not a crash")
    void noSourcesIsHarmless() {
        AdvisorySyncTask task = task(AdvisorySyncProperties.defaults());

        assertThatCode(task::execute).doesNotThrowAnyException();
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("the task type is the one the seeded scheduled_tasks row names")
    void taskTypeMatchesTheMigration() {
        assertThat(AdvisorySyncTask.TASK_TYPE).isEqualTo("security.advisory.sync");
    }

    private AdvisorySyncTask task(AdvisorySyncProperties properties, AdvisorySource... sources) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AdvisorySource> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(sources));
        return new AdvisorySyncTask(ingestService, provider, taskRunner, properties);
    }

    private static AdvisorySource source(String id) {
        AdvisorySource source = mock(AdvisorySource.class);
        when(source.sourceId()).thenReturn(id);
        return source;
    }

    private static AdvisorySyncSummary ok(String id) {
        return new AdvisorySyncSummary(id, true, 3, 120, 4, "cursor", null);
    }

    private static AdvisorySyncSummary failed(String id) {
        return new AdvisorySyncSummary(id, false, 1, 0, 0, null, "IOException: unreachable");
    }
}
