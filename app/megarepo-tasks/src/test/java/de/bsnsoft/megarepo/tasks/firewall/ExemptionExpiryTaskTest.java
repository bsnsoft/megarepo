package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The daily sweep: what it registers itself as, what it calls, and in which
 * order.
 */
class ExemptionExpiryTaskTest {

    private ExemptionService exemptions;
    private TaskRunner taskRunner;
    private ExemptionExpiryTask task;

    @BeforeEach
    void setUp() {
        exemptions = mock(ExemptionService.class);
        taskRunner = mock(TaskRunner.class);
        when(exemptions.notifyUpcomingExpiry(any(), any())).thenReturn(List.of());
        task = new ExemptionExpiryTask(exemptions, ExemptionProperties.defaults(), taskRunner);
    }

    @Test
    @DisplayName("registers under the task type V19 seeds, and runs nothing at startup")
    void registersWithoutRunning() {
        task.register();

        verify(taskRunner).registerHandler(eq("security.firewall.exemption.expiry"), any());
        assertThat(ExemptionExpiryTask.TASK_TYPE).isEqualTo("security.firewall.exemption.expiry");
        verifyNoInteractions(exemptions);
    }

    @Test
    @DisplayName("announces first, expires second — an exemption cannot be warned about and lapsed at once")
    void noticeBeforeExpiry() {
        task.execute();

        InOrder order = inOrder(exemptions);
        order.verify(exemptions).notifyUpcomingExpiry(any(), any());
        order.verify(exemptions).expireLapsed(any());
    }

    @Test
    @DisplayName("the notice window comes from the configured lead time")
    void usesConfiguredLead() {
        task.execute();

        ArgumentCaptor<Duration> lead = ArgumentCaptor.forClass(Duration.class);
        verify(exemptions).notifyUpcomingExpiry(any(Instant.class), lead.capture());
        assertThat(lead.getValue()).isEqualTo(ExemptionProperties.defaults().expiryNoticeLead());
    }
}
