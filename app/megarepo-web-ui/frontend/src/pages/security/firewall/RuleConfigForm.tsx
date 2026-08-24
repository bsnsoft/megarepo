import { useState } from 'react';
import type { FirewallRuleConfigField } from '../../../types/firewall';

/**
 * A rule's configuration, rendered as a form from what the server declares.
 *
 * The alternative — a JSON textarea — is what this replaces, and the reason is
 * not aesthetics. A rule's config is read with `FirewallRuleSettings.number()`,
 * `.flag()`, `.textList()`, all of which fall back to the default on anything
 * they cannot parse. A typo in a JSON blob therefore does not fail: it produces
 * a policy that silently enforces something other than what is written in it.
 * A typed field cannot make that mistake, and a list editor cannot produce
 * `"allowed": "MIT"` where the rule wants an array.
 *
 * Field types the server sends that this does not know render as text and are
 * sent through unchanged — an unknown type is a rule this build of the UI is
 * older than, not a reason to hide the field.
 */

function normalizeType(field: FirewallRuleConfigField): string {
  const type = (field.type ?? '').toLowerCase();
  if (field.allowedValues && field.allowedValues.length > 0) {
    return 'enum';
  }
  if (['boolean', 'bool'].includes(type)) return 'boolean';
  if (['number', 'integer', 'int', 'long', 'double', 'float'].includes(type)) return 'number';
  if (['list', 'array', 'string[]', 'stringlist'].includes(type)) return 'list';
  if (type === 'duration') return 'duration';
  return 'string';
}

function asList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((entry) => String(entry));
  }
  if (typeof value === 'string' && value.trim().length > 0) {
    return value.split(/\s*,\s*/).filter(Boolean);
  }
  return [];
}

export default function RuleConfigForm({
  fields,
  config,
  disabled,
  onChange,
}: {
  fields: FirewallRuleConfigField[];
  config: Record<string, unknown>;
  disabled: boolean;
  onChange: (key: string, value: unknown) => void;
}) {
  if (fields.length === 0) {
    return (
      <p className="text-xs text-gray-500 italic">This rule takes no configuration.</p>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {fields.map((field) => {
        const type = normalizeType(field);
        const label = field.label ?? field.key;
        const id = `rule-config-${field.key}`;
        const current = config[field.key];

        return (
          <div key={field.key} className={type === 'list' ? 'md:col-span-2' : ''}>
            <label htmlFor={id} className="block text-xs font-medium text-gray-700 mb-1">
              {label}
              {field.required && <span className="text-red-600"> *</span>}
              <span className="ml-1.5 font-mono font-normal text-[10px] text-gray-400">
                {field.key}
              </span>
            </label>

            {type === 'boolean' ? (
              <label className="inline-flex items-center gap-2 text-sm text-gray-800">
                <input
                  id={id}
                  type="checkbox"
                  disabled={disabled}
                  checked={
                    current === undefined ? field.defaultValue === true : current === true
                  }
                  onChange={(e) => onChange(field.key, e.target.checked)}
                />
                <span className="text-xs text-gray-500">
                  {field.defaultValue === true ? 'on by default' : 'off by default'}
                </span>
              </label>
            ) : type === 'enum' ? (
              <select
                id={id}
                disabled={disabled}
                value={current === undefined ? String(field.defaultValue ?? '') : String(current)}
                onChange={(e) => onChange(field.key, e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm disabled:bg-gray-50"
              >
                {(field.allowedValues ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            ) : type === 'number' ? (
              <input
                id={id}
                type="number"
                disabled={disabled}
                value={current === undefined ? '' : String(current)}
                placeholder={field.defaultValue == null ? '' : String(field.defaultValue)}
                onChange={(e) =>
                  onChange(field.key, e.target.value === '' ? undefined : Number(e.target.value))
                }
                className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm disabled:bg-gray-50"
              />
            ) : type === 'list' ? (
              <ListField
                id={id}
                disabled={disabled}
                values={asList(current ?? field.defaultValue)}
                onChange={(values) => onChange(field.key, values)}
              />
            ) : (
              <input
                id={id}
                type="text"
                disabled={disabled}
                value={current === undefined ? '' : String(current)}
                placeholder={
                  field.defaultValue == null
                    ? type === 'duration'
                      ? 'e.g. P7D'
                      : ''
                    : String(field.defaultValue)
                }
                onChange={(e) => onChange(field.key, e.target.value === '' ? undefined : e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm font-mono disabled:bg-gray-50"
              />
            )}

            {field.description && (
              <p className="text-[11px] text-gray-500 mt-1">{field.description}</p>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * A list of strings, entered one at a time.
 *
 * Written as chips rather than a comma-separated text field because of the
 * LICENSE rule specifically: `The Apache Software License, Version 2.0` is one
 * license and contains a comma, and any separator-based input silently cuts it
 * in half into two entries that match nothing at all.
 */
function ListField({
  id,
  values,
  disabled,
  onChange,
}: {
  id: string;
  values: string[];
  disabled: boolean;
  onChange: (values: string[]) => void;
}) {
  const [draft, setDraft] = useState('');

  function add() {
    const entry = draft.trim();
    if (entry.length === 0 || values.includes(entry)) {
      setDraft('');
      return;
    }
    onChange([...values, entry]);
    setDraft('');
  }

  return (
    <div>
      <div className="flex gap-2">
        <input
          id={id}
          type="text"
          disabled={disabled}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm font-mono disabled:bg-gray-50"
        />
        <button
          type="button"
          disabled={disabled || draft.trim().length === 0}
          onClick={add}
          className="px-3 py-2 text-sm font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-40"
        >
          Add
        </button>
      </div>
      {values.length > 0 && (
        <ul className="flex flex-wrap gap-1.5 mt-2">
          {values.map((value) => (
            <li
              key={value}
              className="inline-flex items-center gap-1.5 bg-gray-100 rounded px-2 py-1 text-xs font-mono text-gray-800"
            >
              {value}
              <button
                type="button"
                disabled={disabled}
                aria-label={`Remove ${value}`}
                onClick={() => onChange(values.filter((entry) => entry !== value))}
                className="text-gray-400 hover:text-red-600 disabled:opacity-40"
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
