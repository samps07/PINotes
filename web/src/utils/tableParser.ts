import { NoteDocument, TableListData, TableTaskItem } from '../types/note';
import { stripRichTagsToPlainText } from './richText';

/**
 * Exact TypeScript port of Android Note.kt table/checklist parser & serializer.
 */
export function parseTableListData(content: string): TableListData {
  if (!content) return { headers: ['Item'], items: [] };
  const lines = content.split('\n');
  let headers: string[] = [];
  const items: TableTaskItem[] = [];

  for (const line of lines) {
    if (line === '' && lines.length > 1 && items.length === 0 && headers.length === 0) continue;

    if (/^\[HEADERS\] /i.test(line)) {
      const headerStr = line.slice(10);
      if (headerStr.length > 0) {
        headers = headerStr.split(' | ');
      }
    } else if (/^\[HEADERS\]/i.test(line)) {
      const headerStr = line.replace(/^\[HEADERS\]:?/i, '').trim();
      if (headerStr.length > 0) {
        headers = headerStr.split(' | ');
      }
    } else {
      const isChecked = /^\[x\]/i.test(line);
      let rawText = line;
      if (/^\[x\] /i.test(line)) rawText = line.slice(4);
      else if (line.startsWith('[ ] ')) rawText = line.slice(4);
      else if (/^\[x\]/i.test(line)) rawText = line.slice(3);
      else if (line.startsWith('[ ]')) rawText = line.slice(3);

      if (/^\[HEADERS\]/i.test(rawText)) continue;

      let columns: string[];
      if (rawText.includes(' | ')) {
        columns = rawText.split(' | ');
      } else if (rawText.includes('|')) {
        columns = rawText.split('|').map((it) =>
          it.startsWith(' ') && it.endsWith(' ') && it.length >= 2 ? it.slice(1, -1) : it
        );
      } else {
        columns = [rawText];
      }

      items.push({ values: columns, isChecked });
    }
  }

  const maxCols = Math.max(
    headers.length,
    ...items.map((it) => it.values.length),
    1
  );

  if (headers.length === 0) {
    if (maxCols === 1) {
      headers.push('Item');
    } else {
      for (let i = 1; i <= maxCols; i++) {
        headers.push(i === 1 ? 'Item' : `Column ${i}`);
      }
    }
  } else {
    while (headers.length < maxCols) {
      headers.push(`Column ${headers.length + 1}`);
    }
  }

  const normalizedItems = items.map((item) => {
    if (item.values.length < maxCols) {
      const next = [...item.values];
      while (next.length < maxCols) next.push('');
      return { ...item, values: next };
    }
    return item;
  });

  return { headers, items: normalizedItems };
}

export function serializeTableListData(
  data: TableListData,
  sortCheckedToBottom = false
): string {
  const parts: string[] = [];
  const isDefaultSingleHeader =
    data.headers.length === 1 &&
    (data.headers[0] === 'Item' || data.headers[0].trim() === '');

  if (!isDefaultSingleHeader && data.headers.length > 0 && data.headers.some((h) => h.trim() !== '')) {
    parts.push(`[HEADERS] ${data.headers.join(' | ')}`);
  }

  const list = sortCheckedToBottom
    ? [...data.items].sort((a, b) => Number(a.isChecked) - Number(b.isChecked))
    : data.items;

  for (const item of list) {
    const prefix = item.isChecked ? '[x] ' : '[ ] ';
    parts.push(`${prefix}${item.values.join(' | ')}`);
  }

  return parts.join('\n');
}

export function toggleTaskItemChecked(content: string, itemIndex: Int | number): string {
  const tableData = parseTableListData(content);
  if (itemIndex >= 0 && itemIndex < tableData.items.length) {
    const updated = tableData.items.map((item, idx) =>
      idx === itemIndex ? { ...item, isChecked: !item.isChecked } : item
    );
    return serializeTableListData({ ...tableData, items: updated });
  }
  return content;
}

type Int = number;

export function formatNoteForCopying(note: NoteDocument): string {
  const blocks: string[] = [];
  if (note.title.trim()) {
    blocks.push(note.title.trim());
  }

  if (note.isTaskList) {
    const tableData = parseTableListData(note.content);
    if (tableData.headers.length > 1) {
      const headerLine = tableData.headers.join(' | ');
      const sep = tableData.headers.map((h) => '-'.repeat(Math.max(3, h.length))).join(' | ');
      const rows = tableData.items
        .map((item) => `${item.isChecked ? '☑' : '☐'} ${item.values.join(' | ')}`)
        .join('\n');
      blocks.push(`${headerLine}\n${sep}\n${rows}`);
    } else {
      const rows = tableData.items
        .map((item) => `${item.isChecked ? '☑' : '☐'} ${item.values[0] ?? ''}`)
        .join('\n');
      blocks.push(rows);
    }
  } else {
    const plain = stripRichTagsToPlainText(note.content);
    if (plain.trim()) blocks.push(plain);
  }

  if (note.webUrl && note.webUrl.trim() && !note.content.includes(note.webUrl)) {
    blocks.push(note.webUrl.trim());
  }

  return blocks.join('\n\n').trim();
}
