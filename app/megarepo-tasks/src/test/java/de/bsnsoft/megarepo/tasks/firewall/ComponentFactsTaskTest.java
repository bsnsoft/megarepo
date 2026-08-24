package de.bsnsoft.megarepo.tasks.firewall;

import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsResolver;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V19 sweep's wiring.
 *
 * <p>Two things matter and neither is the sweep's own logic: the handler is
 * registered under the exact type the migration seeds — a mismatch means a
 * {@code scheduled_tasks} row that fires into nothing, which is silent — and a
 * broken sweep surfaces as a failed task rather than being swallowed into a
 * green tick.
 */
class ComponentFactsTaskTest {

    @Test
    @DisplayName("the handler is registered under the task type V19 seeds")
    void registersUnderTheSeededTaskType() {
        ComponentFactsResolver resolver = mock(ComponentFactsResolver.class);
        TaskRunner taskRunner = mock(TaskRunner.class);

        new ComponentFactsTask(resolver, taskRunner).register();

        assertThat(ComponentFactsTask.TASK_TYPE).isEqualTo("security.firewall.facts.resolve");
        verify(taskRunner).registerHandler(eq("security.firewall.facts.resolve"), any(Runnable.class));
    }

    @Test
    @DisplayName("running the task sweeps")
    void executeSweeps() {
        ComponentFactsResolver resolver = mock(ComponentFactsResolver.class);
        when(resolver.sweep()).thenReturn(7);

        new ComponentFactsTask(resolver, mock(TaskRunner.class)).execute();

        verify(resolver).sweep();
    }

    @Test
    @DisplayName("a sweep that fails fails the task rather than reporting success")
    void failuresAreNotSwallowed() {
        ComponentFactsResolver resolver = mock(ComponentFactsResolver.class);
        when(resolver.sweep()).thenThrow(new IllegalStateException("database is gone"));
        ComponentFactsTask task = new ComponentFactsTask(resolver, mock(TaskRunner.class));

        assertThatThrownBy(task::execute).isInstanceOf(IllegalStateException.class);
    }
}
