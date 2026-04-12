import { useState, useMemo, type ReactNode } from 'react';

export type SortType = 'string' | 'number' | 'date';

export interface Column<T> {
  key: string;
  label: string;
  sortable?: boolean;
  sortType?: SortType;
  render?: (row: T) => ReactNode;
  width?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  keyField: keyof T;
  onRowClick?: (row: T) => void;
  searchable?: boolean;
  searchPlaceholder?: string;
  emptyMessage?: string;
  actions?: ReactNode;
  pageSize?: number;
  defaultSortKey?: string;
  defaultSortDir?: SortDir;
}

type SortDir = 'asc' | 'desc';

function compareValues(av: unknown, bv: unknown, sortType: SortType): number {
  if (av == null && bv == null) return 0;
  if (av == null) return 1;
  if (bv == null) return -1;

  switch (sortType) {
    case 'number': {
      const an = typeof av === 'number' ? av : Number(av);
      const bn = typeof bv === 'number' ? bv : Number(bv);
      if (isNaN(an) && isNaN(bn)) return 0;
      if (isNaN(an)) return 1;
      if (isNaN(bn)) return -1;
      return an - bn;
    }
    case 'date': {
      const ad = typeof av === 'number' ? av : new Date(String(av)).getTime();
      const bd = typeof bv === 'number' ? bv : new Date(String(bv)).getTime();
      if (isNaN(ad) && isNaN(bd)) return 0;
      if (isNaN(ad)) return 1;
      if (isNaN(bd)) return -1;
      return ad - bd;
    }
    default:
      return String(av).localeCompare(String(bv), undefined, { numeric: true });
  }
}

function SortChevron({ active, direction }: { active: boolean; direction: SortDir }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 12 12"
      fill="none"
      className={`transition-colors ${active ? 'text-gray-700' : 'text-gray-300'}`}
    >
      {direction === 'asc' ? (
        <path d="M3 7.5L6 4.5L9 7.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      ) : (
        <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      )}
    </svg>
  );
}

export default function DataTable<T extends Record<string, unknown>>({
  columns,
  data,
  keyField,
  onRowClick,
  searchable = true,
  searchPlaceholder = 'Filter...',
  emptyMessage = 'No items found',
  actions,
  pageSize = 20,
  defaultSortKey,
  defaultSortDir = 'asc',
}: DataTableProps<T>) {
  const [filter, setFilter] = useState('');
  const [sortKey, setSortKey] = useState<string | null>(defaultSortKey ?? null);
  const [sortDir, setSortDir] = useState<SortDir>(defaultSortDir);
  const [page, setPage] = useState(0);

  const filtered = useMemo(() => {
    if (!filter) return data;
    const lower = filter.toLowerCase();
    return data.filter((row) =>
      columns.some((col) => {
        const val = row[col.key];
        return val != null && String(val).toLowerCase().includes(lower);
      }),
    );
  }, [data, filter, columns]);

  const sorted = useMemo(() => {
    if (!sortKey) return filtered;
    const col = columns.find((c) => c.key === sortKey);
    const sortType = col?.sortType ?? 'string';
    return [...filtered].sort((a, b) => {
      const cmp = compareValues(a[sortKey], b[sortKey], sortType);
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [filtered, sortKey, sortDir, columns]);

  const totalPages = Math.ceil(sorted.length / pageSize);
  const paged = sorted.slice(page * pageSize, (page + 1) * pageSize);

  function handleSort(key: string) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function handleFilterChange(value: string) {
    setFilter(value);
    setPage(0);
  }

  return (
    <div className="w-full">
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 sm:gap-4 px-4 py-3 border-b border-gray-200">
        {searchable && (
          <div className="flex items-center gap-2 flex-1 sm:max-w-xs">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" strokeWidth="2">
              <circle cx="11" cy="11" r="8" />
              <path d="M21 21l-4.35-4.35" />
            </svg>
            <input
              type="text"
              placeholder={searchPlaceholder}
              value={filter}
              onChange={(e) => handleFilterChange(e.target.value)}
              className="w-full py-2 px-3 border border-gray-200 rounded-md text-sm bg-white text-gray-700 placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
            />
          </div>
        )}
        {actions && <div className="flex gap-2 shrink-0">{actions}</div>}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr>
              {columns.map((col) => (
                <th
                  key={col.key}
                  style={col.width ? { width: col.width } : undefined}
                  className={`text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200 whitespace-nowrap select-none ${
                    col.sortable ? 'cursor-pointer hover:text-gray-700' : ''
                  }`}
                  onClick={col.sortable ? () => handleSort(col.key) : undefined}
                >
                  <span className="inline-flex items-center gap-1">
                    {col.label}
                    {col.sortable && (
                      <SortChevron
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                      />
                    )}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {paged.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="text-center py-10 px-4 text-sm text-gray-400">
                  {emptyMessage}
                </td>
              </tr>
            ) : (
              paged.map((row) => (
                <tr
                  key={String(row[keyField])}
                  className={`border-b border-gray-100 hover:bg-gray-50 transition-colors ${
                    onRowClick ? 'cursor-pointer' : ''
                  }`}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                >
                  {columns.map((col) => (
                    <td key={col.key} className="px-4 py-3 text-sm text-gray-700 align-middle">
                      {col.render ? col.render(row) : String(row[col.key] ?? '')}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="flex flex-col sm:flex-row items-center justify-between gap-2 px-4 py-3 border-t border-gray-200">
          <span className="text-xs text-gray-500">
            Showing {page * pageSize + 1}–{Math.min((page + 1) * pageSize, sorted.length)} of {sorted.length}
          </span>
          <div className="flex gap-1.5">
            <button
              className="inline-flex items-center px-3 py-1.5 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-md hover:bg-gray-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </button>
            <button
              className="inline-flex items-center px-3 py-1.5 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-md hover:bg-gray-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              disabled={page >= totalPages - 1}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
