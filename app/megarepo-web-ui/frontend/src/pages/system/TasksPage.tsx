import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { TaskXO } from '../../types/api';

type TaskRow = TaskXO & Record<string, unknown>;

const TASK_TYPES = [
  { value: 'repository.cleanup', label: 'Repository Cleanup' },
  { value: 'blobstore.compact', label: 'Blob Store Compaction' },
  { value: 'proxy.negative-cache.purge', label: 'Purge Proxy Negative Cache' },
  { value: 'docker.gc', label: 'Docker Garbage Collection' },
];

const SCHEDULE_PRESETS = [
  { value: '', label: 'Custom cron expression' },
  { value: '0 0 * * * ?', label: 'Hourly' },
  { value: '0 0 3 * * ?', label: 'Daily (3:00 AM)' },
  { value: '0 0 3 ? * MON', label: 'Weekly (Monday 3:00 AM)' },
];

interface CreateTaskForm {
  name: string;
  type: string;
  cronExpression: string;
  enabled: boolean;
}

function CreateTaskDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const { showToast } = useToast();
  const [form, setForm] = useState<CreateTaskForm>({
    name: '',
    type: TASK_TYPES[0].value,
    cronExpression: SCHEDULE_PRESETS[1].value,
    enabled: true,
  });
  const [preset, setPreset] = useState(SCHEDULE_PRESETS[1].value);
  const [submitting, setSubmitting] = useState(false);

  function resetForm() {
    setForm({ name: '', type: TASK_TYPES[0].value, cronExpression: SCHEDULE_PRESETS[1].value, enabled: true });
    setPreset(SCHEDULE_PRESETS[1].value);
  }

  function handlePresetChange(value: string) {
    setPreset(value);
    if (value) {
      setForm((f) => ({ ...f, cronExpression: value }));
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim() || !form.type.trim()) return;
    setSubmitting(true);
    try {
      await api.post('/tasks', {
        name: form.name.trim(),
        type: form.type,
        cronExpression: form.cronExpression.trim() || null,
        enabled: form.enabled,
      });
      showToast('success', 'Task created');
      resetForm();
      onCreated();
      onClose();
    } catch {
      showToast('error', 'Failed to create task');
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  const inputClass =
    'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';
  const labelClass = 'block text-xs font-medium text-gray-600 mb-1';

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-lg w-[520px] max-w-[90vw] shadow-lg animate-[slideUp_0.15s_ease]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-6 py-4 border-b border-gray-200">
          <h3 className="text-base font-semibold text-gray-900">Create Task</h3>
          <p className="text-xs text-gray-500 mt-0.5">Schedule a new background task</p>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="px-6 py-4 space-y-4">
            <div>
              <label className={labelClass}>Name</label>
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="My cleanup task"
                className={inputClass}
                required
                autoFocus
              />
            </div>
            <div>
              <label className={labelClass}>Type</label>
              <select
                value={form.type}
                onChange={(e) => setForm({ ...form, type: e.target.value })}
                className={inputClass}
              >
                {TASK_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className={labelClass}>Schedule</label>
              <select
                value={preset}
                onChange={(e) => handlePresetChange(e.target.value)}
                className={`${inputClass} mb-2`}
              >
                {SCHEDULE_PRESETS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
              <input
                type="text"
                value={form.cronExpression}
                onChange={(e) => {
                  setForm({ ...form, cronExpression: e.target.value });
                  setPreset('');
                }}
                placeholder="0 0 3 * * ?"
                className={inputClass}
              />
              <p className="text-xs text-gray-400 mt-1">
                Quartz cron format: sec min hour day month weekday
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                role="switch"
                aria-checked={form.enabled}
                onClick={() => setForm({ ...form, enabled: !form.enabled })}
                className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-blue-600 focus:ring-offset-2 ${form.enabled ? 'bg-blue-600' : 'bg-gray-200'}`}
              >
                <span
                  className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ${form.enabled ? 'translate-x-4' : 'translate-x-0'}`}
                />
              </button>
              <span className="text-sm text-gray-700">Enabled</span>
            </div>
          </div>
          <div className="px-6 py-4 border-t border-gray-200 flex gap-2 justify-end">
            <button
              type="button"
              className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
              onClick={() => { resetForm(); onClose(); }}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="inline-flex items-center justify-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50"
              disabled={submitting || !form.name.trim()}
            >
              {submitting ? 'Creating...' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function TasksPage() {
  const { showToast } = useToast();
  const [tasks, setTasks] = useState<TaskRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [stopTarget, setStopTarget] = useState<TaskRow | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  function loadTasks() {
    setLoading(true);
    api
      .get<TaskXO[]>('/tasks')
      .then((data) => setTasks(data as TaskRow[]))
      .catch(() => showToast('error', 'Failed to load tasks'))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadTasks();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleRun(id: string, e: React.MouseEvent) {
    e.stopPropagation();
    try {
      await api.post(`/tasks/${id}/run`);
      showToast('success', 'Task started');
      loadTasks();
    } catch {
      showToast('error', 'Failed to run task');
    }
  }

  async function handleStop() {
    if (!stopTarget) return;
    try {
      await api.post(`/tasks/${stopTarget.id}/stop`);
      showToast('success', 'Task stopped');
      setStopTarget(null);
      loadTasks();
    } catch {
      showToast('error', 'Failed to stop task');
    }
  }

  const columns: Column<TaskRow>[] = useMemo(
    () => [
      {
        key: 'name',
        label: 'Name',
        sortable: true,
        render: (row) => <span className="font-medium text-gray-900">{row.name}</span>,
      },
      {
        key: 'type',
        label: 'Type',
        sortable: true,
        width: '140px',
        render: (row) => <Badge variant="default">{row.type}</Badge>,
      },
      {
        key: 'currentState',
        label: 'State',
        sortable: true,
        width: '100px',
        render: (row) => {
          const variant = row.currentState === 'RUNNING' ? 'warning' : row.enabled ? 'success' : 'default';
          return <Badge variant={variant}>{row.currentState || (row.enabled ? 'Idle' : 'Disabled')}</Badge>;
        },
      },
      {
        key: 'cronExpression',
        label: 'Schedule',
        width: '120px',
        render: (row) => (
          <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
            {row.cronExpression || '-'}
          </code>
        ),
      },
      {
        key: 'lastRunResult',
        label: 'Last Result',
        width: '110px',
        render: (row) => <span className="text-gray-600">{row.lastRunResult || '-'}</span>,
      },
      {
        key: '_actions',
        label: '',
        width: '140px',
        render: (row) => (
          <div className="flex gap-2">
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-blue-50 text-blue-600 text-xs font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={(e) => handleRun(row.id, e)}
              disabled={row.currentState === 'RUNNING'}
            >
              Run
            </button>
            {row.currentState === 'RUNNING' && (
              <button
                className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-amber-50 text-amber-600 text-xs font-medium rounded-md transition-colors"
                onClick={(e) => {
                  e.stopPropagation();
                  setStopTarget(row);
                }}
              >
                Stop
              </button>
            )}
          </div>
        ),
      },
    ],
    [], // eslint-disable-line react-hooks/exhaustive-deps
  );

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading tasks..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Tasks</h1>
          <p className="text-sm text-gray-500 mt-1">Scheduled background tasks</p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={() => setShowCreate(true)}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Create Task
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={tasks}
          keyField="id"
          searchPlaceholder="Filter tasks..."
          emptyMessage="No tasks configured"
        />
      </div>

      <ConfirmDialog
        open={stopTarget !== null}
        title="Stop Task"
        message={`Are you sure you want to stop "${stopTarget?.name}"?`}
        confirmLabel="Stop"
        variant="danger"
        onConfirm={handleStop}
        onCancel={() => setStopTarget(null)}
      />

      <CreateTaskDialog
        open={showCreate}
        onClose={() => setShowCreate(false)}
        onCreated={loadTasks}
      />
    </div>
  );
}
