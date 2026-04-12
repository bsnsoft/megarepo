import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import EmptyState from '../../components/EmptyState';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { LdapServerXO } from '../../types/api';

const DEFAULT_SERVER: Omit<LdapServerXO, 'sortOrder'> = {
  name: '',
  protocol: 'ldap',
  hostname: '',
  port: 389,
  searchBase: '',
  authScheme: 'simple',
  authUsername: null,
  authPassword: null,
  connectionTimeout: 30,
  retryDelay: 0,
  maxRetries: 3,
  userBaseDn: 'ou=users',
  userSubtree: true,
  userObjectClass: 'inetOrgPerson',
  userIdAttribute: 'uid',
  userNameAttribute: 'cn',
  userEmailAttribute: 'mail',
  ldapGroupsAsRoles: false,
  groupType: 'static',
  groupBaseDn: 'ou=groups',
  groupSubtree: true,
  groupObjectClass: 'groupOfUniqueNames',
  groupIdAttribute: 'cn',
  groupMemberAttribute: 'uniqueMember',
  groupMemberFormat: null,
  userMemberOfAttribute: null,
  enabled: true,
};

interface FormField {
  label: string;
  key: keyof typeof DEFAULT_SERVER;
  type?: 'text' | 'number' | 'password' | 'select' | 'checkbox';
  options?: { value: string; label: string }[];
  placeholder?: string;
  required?: boolean;
  section: string;
}

const FORM_FIELDS: FormField[] = [
  { label: 'Name', key: 'name', required: true, placeholder: 'e.g. corporate-ldap', section: 'Connection' },
  {
    label: 'Protocol',
    key: 'protocol',
    type: 'select',
    options: [
      { value: 'ldap', label: 'ldap' },
      { value: 'ldaps', label: 'ldaps' },
    ],
    section: 'Connection',
  },
  { label: 'Hostname', key: 'hostname', required: true, placeholder: 'ldap.example.com', section: 'Connection' },
  { label: 'Port', key: 'port', type: 'number', section: 'Connection' },
  { label: 'Search Base', key: 'searchBase', required: true, placeholder: 'dc=example,dc=com', section: 'Connection' },
  {
    label: 'Auth Scheme',
    key: 'authScheme',
    type: 'select',
    options: [
      { value: 'simple', label: 'Simple Authentication' },
      { value: 'none', label: 'Anonymous' },
      { value: 'DIGEST-MD5', label: 'DIGEST-MD5' },
      { value: 'CRAM-MD5', label: 'CRAM-MD5' },
    ],
    section: 'Connection',
  },
  { label: 'Auth Username', key: 'authUsername', placeholder: 'cn=admin,dc=example,dc=com', section: 'Connection' },
  { label: 'Auth Password', key: 'authPassword', type: 'password', placeholder: 'Password', section: 'Connection' },
  { label: 'Connection Timeout (s)', key: 'connectionTimeout', type: 'number', section: 'Connection' },
  { label: 'Retry Delay (s)', key: 'retryDelay', type: 'number', section: 'Connection' },
  { label: 'Max Retries', key: 'maxRetries', type: 'number', section: 'Connection' },
  { label: 'User Base DN', key: 'userBaseDn', placeholder: 'ou=users', section: 'User Mapping' },
  { label: 'User Subtree', key: 'userSubtree', type: 'checkbox', section: 'User Mapping' },
  { label: 'User Object Class', key: 'userObjectClass', placeholder: 'inetOrgPerson', section: 'User Mapping' },
  { label: 'User ID Attribute', key: 'userIdAttribute', placeholder: 'uid', section: 'User Mapping' },
  { label: 'User Name Attribute', key: 'userNameAttribute', placeholder: 'cn', section: 'User Mapping' },
  { label: 'User Email Attribute', key: 'userEmailAttribute', placeholder: 'mail', section: 'User Mapping' },
  { label: 'Map LDAP Groups as Roles', key: 'ldapGroupsAsRoles', type: 'checkbox', section: 'Group Mapping' },
  {
    label: 'Group Type',
    key: 'groupType',
    type: 'select',
    options: [
      { value: 'static', label: 'Static Groups' },
      { value: 'dynamic', label: 'Dynamic Groups' },
    ],
    section: 'Group Mapping',
  },
  { label: 'Group Base DN', key: 'groupBaseDn', placeholder: 'ou=groups', section: 'Group Mapping' },
  { label: 'Group Subtree', key: 'groupSubtree', type: 'checkbox', section: 'Group Mapping' },
  { label: 'Group Object Class', key: 'groupObjectClass', placeholder: 'groupOfUniqueNames', section: 'Group Mapping' },
  { label: 'Group ID Attribute', key: 'groupIdAttribute', placeholder: 'cn', section: 'Group Mapping' },
  { label: 'Group Member Attribute', key: 'groupMemberAttribute', placeholder: 'uniqueMember', section: 'Group Mapping' },
  { label: 'Group Member Format', key: 'groupMemberFormat', placeholder: 'uid=${username},ou=users,dc=example,dc=com', section: 'Group Mapping' },
  { label: 'User Member-Of Attribute', key: 'userMemberOfAttribute', placeholder: 'memberOf', section: 'Group Mapping' },
  { label: 'Enabled', key: 'enabled', type: 'checkbox', section: 'Connection' },
];

export default function LdapPage() {
  const { showToast } = useToast();
  const [servers, setServers] = useState<LdapServerXO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showDialog, setShowDialog] = useState(false);
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [formData, setFormData] = useState<Record<string, unknown>>({ ...DEFAULT_SERVER });

  function loadServers() {
    api
      .get<LdapServerXO[]>('/security/ldap')
      .then(setServers)
      .catch(() => setServers([]))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadServers();
  }, []);

  function openAddDialog() {
    setFormData({ ...DEFAULT_SERVER });
    setShowDialog(true);
  }

  async function handleSave() {
    setSaving(true);
    try {
      const payload = { ...formData, sortOrder: servers.length };
      await api.post('/security/ldap', payload);
      showToast('success', `LDAP server "${formData.name}" created`);
      setShowDialog(false);
      loadServers();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to create LDAP server';
      showToast('error', message);
    } finally {
      setSaving(false);
    }
  }

  async function handleVerify(name: string) {
    setVerifying(name);
    try {
      const result = await api.post<{ success: boolean }>(`/security/ldap/${encodeURIComponent(name)}/verify`);
      if (result.success) {
        showToast('success', `LDAP server "${name}" connection verified`);
      } else {
        showToast('error', `LDAP server "${name}" connection failed`);
      }
    } catch {
      showToast('error', `Failed to verify "${name}"`);
    } finally {
      setVerifying(null);
    }
  }

  async function handleDelete(name: string) {
    try {
      await api.delete(`/security/ldap/${encodeURIComponent(name)}`);
      showToast('success', `LDAP server "${name}" deleted`);
      setDeleteTarget(null);
      loadServers();
    } catch {
      showToast('error', `Failed to delete "${name}"`);
    }
  }

  function updateField(key: string, value: unknown) {
    setFormData((prev) => ({ ...prev, [key]: value }));
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading LDAP configuration..." />
      </div>
    );
  }

  const sections = [...new Set(FORM_FIELDS.map((f) => f.section))];

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">LDAP Configuration</h1>
          <p className="text-sm text-gray-500 mt-1">Configure LDAP server connections for user authentication</p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors shrink-0"
          onClick={openAddDialog}
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          Add Server
        </button>
      </div>

      {servers.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <EmptyState
            icon={
              <svg className="mx-auto h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M21.75 17.25v-.228a4.5 4.5 0 00-.12-1.03l-2.268-9.64a3.375 3.375 0 00-3.285-2.602H7.923a3.375 3.375 0 00-3.285 2.602l-2.268 9.64a4.5 4.5 0 00-.12 1.03v.228m19.5 0a3 3 0 01-3 3H5.25a3 3 0 01-3-3m19.5 0a3 3 0 00-3-3H5.25a3 3 0 00-3 3m16.5 0h.008v.008h-.008v-.008zm-3 0h.008v.008h-.008v-.008z" />
              </svg>
            }
            title="No LDAP Servers Configured"
            description="Add an LDAP server to enable directory-based authentication for your users."
            action={
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
                onClick={openAddDialog}
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
                </svg>
                Add LDAP Server
              </button>
            }
          />
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full min-w-[600px]">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">#</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">URL</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Auth</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Search Base</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {servers.map((server, index) => (
                <tr key={server.name} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3 text-sm text-gray-500 tabular-nums">{index + 1}</td>
                  <td className="px-4 py-3 text-sm">
                    <span className="font-medium text-gray-900">{server.name}</span>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                      {server.protocol}://{server.hostname}:{server.port}
                    </code>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <Badge variant="default">{server.authScheme}</Badge>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-700">{server.searchBase}</td>
                  <td className="px-4 py-3 text-sm">
                    <Badge variant={server.enabled ? 'success' : 'default'}>
                      {server.enabled ? 'Enabled' : 'Disabled'}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <div className="flex gap-2">
                      <button
                        className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-blue-50 text-blue-600 text-xs font-medium rounded-md transition-colors disabled:opacity-50"
                        onClick={() => handleVerify(server.name)}
                        disabled={verifying === server.name}
                      >
                        {verifying === server.name ? 'Verifying...' : 'Verify'}
                      </button>
                      <button
                        className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
                        onClick={() => setDeleteTarget(server.name)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Add Server Dialog */}
      {showDialog && (
        <div
          className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
          onClick={() => setShowDialog(false)}
        >
          <div
            className="bg-white rounded-lg w-[640px] max-w-[90vw] max-h-[85vh] shadow-lg animate-[slideUp_0.15s_ease] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-6 py-4 border-b border-gray-200">
              <h3 className="text-base font-semibold text-gray-900">Add LDAP Server</h3>
              <p className="text-sm text-gray-500 mt-0.5">Configure a new LDAP directory connection</p>
            </div>
            <div className="overflow-y-auto px-6 py-4 flex-1">
              {sections.map((section) => (
                <div key={section} className="mb-6">
                  <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">{section}</h4>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                    {FORM_FIELDS.filter((f) => f.section === section).map((field) => {
                      const value = formData[field.key];
                      if (field.type === 'checkbox') {
                        return (
                          <label key={field.key} className="col-span-2 flex items-center gap-2 text-sm text-gray-700">
                            <input
                              type="checkbox"
                              checked={!!value}
                              onChange={(e) => updateField(field.key, e.target.checked)}
                              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                            />
                            {field.label}
                          </label>
                        );
                      }
                      if (field.type === 'select') {
                        return (
                          <div key={field.key} className="flex flex-col gap-1">
                            <label className="text-xs font-medium text-gray-600">{field.label}</label>
                            <select
                              value={String(value ?? '')}
                              onChange={(e) => updateField(field.key, e.target.value)}
                              className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                            >
                              {field.options?.map((opt) => (
                                <option key={opt.value} value={opt.value}>
                                  {opt.label}
                                </option>
                              ))}
                            </select>
                          </div>
                        );
                      }
                      return (
                        <div key={field.key} className="flex flex-col gap-1">
                          <label className="text-xs font-medium text-gray-600">
                            {field.label}
                            {field.required && <span className="text-red-400 ml-0.5">*</span>}
                          </label>
                          <input
                            type={field.type ?? 'text'}
                            value={value != null ? String(value) : ''}
                            onChange={(e) =>
                              updateField(field.key, field.type === 'number' ? Number(e.target.value) : e.target.value)
                            }
                            placeholder={field.placeholder}
                            className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                          />
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
            <div className="px-6 py-4 border-t border-gray-200 flex gap-2 justify-end">
              <button
                className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => setShowDialog(false)}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center justify-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50"
                onClick={handleSave}
                disabled={saving || !formData.name || !formData.hostname || !formData.searchBase}
              >
                {saving ? 'Creating...' : 'Create Server'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation */}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete LDAP Server"
        message={`Are you sure you want to delete the LDAP server "${deleteTarget}"? Users authenticating through this server will no longer be able to log in.`}
        confirmLabel="Delete"
        variant="danger"
        onConfirm={() => deleteTarget && handleDelete(deleteTarget)}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
