import React, { useState } from 'react';
import {
  Plus,
  Trash2,
  Columns3,
  ArrowUpDown,
  CheckSquare,
  Square,
  X,
} from 'lucide-react';
import { parseTableListData, serializeTableListData } from '../utils/tableParser';

interface TableChecklistEditorProps {
  value: string;
  onChange: (serialized: string) => void;
}

export const TableChecklistEditor: React.FC<TableChecklistEditorProps> = ({
  value,
  onChange,
}) => {
  const data = parseTableListData(value);
  const [newRowValues, setNewRowValues] = useState<string[]>(() =>
    data.headers.map(() => '')
  );

  const isMultiColumn = data.headers.length > 1;

  const emit = (nextData: typeof data, sortChecked = false) => {
    onChange(serializeTableListData(nextData, sortChecked));
  };

  const handleToggleRow = (idx: number) => {
    const items = data.items.map((item, i) =>
      i === idx ? { ...item, isChecked: !item.isChecked } : item
    );
    emit({ ...data, items });
  };

  const handleCellChange = (rowIdx: number, colIdx: number, text: string) => {
    const items = data.items.map((item, i) => {
      if (i !== rowIdx) return item;
      const values = [...item.values];
      values[colIdx] = text;
      return { ...item, values };
    });
    emit({ ...data, items });
  };

  const handleHeaderChange = (colIdx: number, text: string) => {
    const headers = [...data.headers];
    headers[colIdx] = text;
    emit({ ...data, headers });
  };

  const handleAddColumn = () => {
    const headers = [...data.headers, `Column ${data.headers.length + 1}`];
    const items = data.items.map((item) => ({
      ...item,
      values: [...item.values, ''],
    }));
    setNewRowValues((prev) => [...prev, '']);
    emit({ headers, items });
  };

  const handleRemoveColumn = (colIdx: number) => {
    if (data.headers.length <= 1) return;
    const headers = data.headers.filter((_, i) => i !== colIdx);
    const items = data.items.map((item) => ({
      ...item,
      values: item.values.filter((_, i) => i !== colIdx),
    }));
    setNewRowValues((prev) => prev.filter((_, i) => i !== colIdx));
    emit({ headers, items });
  };

  const handleDeleteRow = (rowIdx: number) => {
    const items = data.items.filter((_, i) => i !== rowIdx);
    emit({ ...data, items });
  };

  const handleAddRow = () => {
    const normalized = data.headers.map((_, idx) => (newRowValues[idx] || '').trim());
    if (normalized.every((v) => !v)) return;
    const items = [...data.items, { values: normalized, isChecked: false }];
    setNewRowValues(data.headers.map(() => ''));
    emit({ ...data, items });
  };

  const handleSortChecked = () => {
    emit(data, true);
  };

  return (
    <div
      className="rounded-2xl border p-3 space-y-3"
      style={{
        background: 'rgba(0,0,0,0.12)',
        borderColor: 'var(--border-subtle)',
      }}
    >
      {/* Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs font-semibold opacity-75">
          {isMultiColumn ? 'Multi-Column Table List' : 'Interactive Checklist'}
        </span>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={handleAddColumn}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium border hover:bg-white/10 cursor-pointer"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            <Columns3 className="w-3.5 h-3.5" />
            Add Column
          </button>
          <button
            type="button"
            onClick={handleSortChecked}
            title="Move checked items to bottom"
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium border hover:bg-white/10 cursor-pointer"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            <ArrowUpDown className="w-3.5 h-3.5" />
            Sort Done
          </button>
        </div>
      </div>

      {/* Table Headers (shown if multi-column or customized) */}
      {isMultiColumn && (
        <div className="flex items-center gap-2 pl-7 pr-8 overflow-x-auto">
          {data.headers.map((header, colIdx) => (
            <div key={colIdx} className="flex-1 min-w-[100px] flex items-center gap-1">
              <input
                type="text"
                value={header}
                onChange={(e) => handleHeaderChange(colIdx, e.target.value)}
                placeholder={`Col ${colIdx + 1}`}
                className="w-full rounded-lg px-2 py-1 text-xs font-bold border bg-black/15 focus:outline-none"
                style={{ borderColor: 'var(--border-subtle)' }}
              />
              {data.headers.length > 1 && (
                <button
                  type="button"
                  onClick={() => handleRemoveColumn(colIdx)}
                  title="Remove column"
                  className="p-1 opacity-60 hover:opacity-100 cursor-pointer"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Existing Rows */}
      <div className="space-y-1.5 max-h-60 overflow-y-auto pr-1">
        {data.items.map((item, rowIdx) => (
          <div key={rowIdx} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => handleToggleRow(rowIdx)}
              className="p-1 rounded hover:bg-white/10 cursor-pointer shrink-0"
              title={item.isChecked ? 'Uncheck' : 'Check'}
            >
              {item.isChecked ? (
                <CheckSquare className="w-4 h-4" style={{ color: 'var(--accent)' }} />
              ) : (
                <Square className="w-4 h-4 opacity-60" />
              )}
            </button>

            <div className="flex-1 flex items-center gap-2 overflow-x-auto">
              {data.headers.map((_, colIdx) => (
                <input
                  key={colIdx}
                  type="text"
                  value={item.values[colIdx] ?? ''}
                  onChange={(e) => handleCellChange(rowIdx, colIdx, e.target.value)}
                  className={`flex-1 min-w-[100px] rounded-lg px-2.5 py-1.5 text-sm border bg-transparent focus:outline-none focus:bg-black/15 ${
                    item.isChecked ? 'line-through opacity-55' : ''
                  }`}
                  style={{ borderColor: 'var(--border-subtle)' }}
                />
              ))}
            </div>

            <button
              type="button"
              onClick={() => handleDeleteRow(rowIdx)}
              className="p-1.5 rounded-lg opacity-50 hover:opacity-100 hover:text-rose-400 cursor-pointer shrink-0"
              title="Delete row"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
      </div>

      {/* Add New Row */}
      <div className="flex items-center gap-2 pt-1 border-t" style={{ borderColor: 'var(--border-subtle)' }}>
        <Plus className="w-4 h-4 opacity-50 ml-1 shrink-0" />
        <div className="flex-1 flex items-center gap-2 overflow-x-auto">
          {data.headers.map((header, colIdx) => (
            <input
              key={colIdx}
              type="text"
              value={newRowValues[colIdx] ?? ''}
              onChange={(e) => {
                const next = data.headers.map((_, i) => newRowValues[i] ?? '');
                next[colIdx] = e.target.value;
                setNewRowValues(next);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleAddRow();
                }
              }}
              placeholder={isMultiColumn ? header : 'List item...'}
              className="flex-1 min-w-[100px] rounded-lg px-2.5 py-1.5 text-sm border bg-black/10 focus:outline-none"
              style={{ borderColor: 'var(--border-subtle)' }}
            />
          ))}
        </div>
        <button
          type="button"
          onClick={handleAddRow}
          className="px-3 py-1.5 rounded-xl text-xs font-semibold cursor-pointer shrink-0"
          style={{
            background: 'var(--accent-container)',
            color: 'var(--accent-container-text)',
          }}
        >
          Add
        </button>
      </div>
    </div>
  );
};
