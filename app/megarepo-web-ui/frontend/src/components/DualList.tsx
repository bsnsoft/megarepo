import { useState } from 'react';

interface DualListProps {
  availableLabel?: string;
  selectedLabel?: string;
  available: string[];
  selected: string[];
  onChange: (selected: string[]) => void;
}

export default function DualList({
  availableLabel = 'Available',
  selectedLabel = 'Selected',
  available,
  selected,
  onChange,
}: DualListProps) {
  const [highlightedAvailable, setHighlightedAvailable] = useState<string[]>([]);
  const [highlightedSelected, setHighlightedSelected] = useState<string[]>([]);

  const unselected = available.filter((item) => !selected.includes(item));

  function moveRight() {
    onChange([...selected, ...highlightedAvailable]);
    setHighlightedAvailable([]);
  }

  function moveLeft() {
    onChange(selected.filter((item) => !highlightedSelected.includes(item)));
    setHighlightedSelected([]);
  }

  function toggleInList(item: string, list: string[], setter: (v: string[]) => void) {
    if (list.includes(item)) {
      setter(list.filter((i) => i !== item));
    } else {
      setter([...list, item]);
    }
  }

  return (
    <div className="dual-list">
      <div className="dual-list-panel">
        <div className="dual-list-label">{availableLabel}</div>
        <div className="dual-list-items">
          {unselected.map((item) => (
            <div
              key={item}
              className={`dual-list-item ${highlightedAvailable.includes(item) ? 'highlighted' : ''}`}
              onClick={() => toggleInList(item, highlightedAvailable, setHighlightedAvailable)}
            >
              {item}
            </div>
          ))}
          {unselected.length === 0 && <div className="dual-list-empty">No items</div>}
        </div>
      </div>

      <div className="dual-list-controls">
        <button className="inline-flex items-center justify-center px-3 py-1.5 text-xs font-medium bg-white border border-gray-200 text-gray-700 rounded-md hover:bg-gray-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed" onClick={moveRight} disabled={highlightedAvailable.length === 0}>
          &rarr;
        </button>
        <button className="inline-flex items-center justify-center px-3 py-1.5 text-xs font-medium bg-white border border-gray-200 text-gray-700 rounded-md hover:bg-gray-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed" onClick={moveLeft} disabled={highlightedSelected.length === 0}>
          &larr;
        </button>
      </div>

      <div className="dual-list-panel">
        <div className="dual-list-label">{selectedLabel}</div>
        <div className="dual-list-items">
          {selected.map((item) => (
            <div
              key={item}
              className={`dual-list-item ${highlightedSelected.includes(item) ? 'highlighted' : ''}`}
              onClick={() => toggleInList(item, highlightedSelected, setHighlightedSelected)}
            >
              {item}
            </div>
          ))}
          {selected.length === 0 && <div className="dual-list-empty">No items</div>}
        </div>
      </div>
    </div>
  );
}
