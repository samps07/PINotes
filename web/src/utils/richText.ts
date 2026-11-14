/**
 * Rich text parser & serializer compatible with Android RichTextHelper.kt
 * Supports <b>, <strong>, <i>, <em>, <u> and Markdown **bold**, *italic*, __underline__.
 * Serializes back to canonical Android <b>, <i>, <u> + \n format.
 */

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

interface TextSegment {
  text: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
}

export function parseAndroidRichTextToSegments(raw: string): TextSegment[] {
  if (!raw) return [];

  const boldRanges: [number, number][] = [];
  const italicRanges: [number, number][] = [];
  const underlineRanges: [number, number][] = [];

  let plain = '';
  let i = 0;
  const len = raw.length;
  const boldStack: number[] = [];
  const italicStack: number[] = [];
  const underlineStack: number[] = [];

  while (i < len) {
    const rest = raw.slice(i);
    if (/^<b>/i.test(rest)) {
      boldStack.push(plain.length);
      i += 3;
    } else if (/^<\/b>/i.test(rest)) {
      const s = boldStack.pop();
      if (s !== undefined && s < plain.length) boldRanges.push([s, plain.length]);
      i += 4;
    } else if (/^<strong>/i.test(rest)) {
      boldStack.push(plain.length);
      i += 8;
    } else if (/^<\/strong>/i.test(rest)) {
      const s = boldStack.pop();
      if (s !== undefined && s < plain.length) boldRanges.push([s, plain.length]);
      i += 9;
    } else if (/^<i>/i.test(rest)) {
      italicStack.push(plain.length);
      i += 3;
    } else if (/^<\/i>/i.test(rest)) {
      const s = italicStack.pop();
      if (s !== undefined && s < plain.length) italicRanges.push([s, plain.length]);
      i += 4;
    } else if (/^<em>/i.test(rest)) {
      italicStack.push(plain.length);
      i += 4;
    } else if (/^<\/em>/i.test(rest)) {
      const s = italicStack.pop();
      if (s !== undefined && s < plain.length) italicRanges.push([s, plain.length]);
      i += 5;
    } else if (/^<u>/i.test(rest)) {
      underlineStack.push(plain.length);
      i += 3;
    } else if (/^<\/u>/i.test(rest)) {
      const s = underlineStack.pop();
      if (s !== undefined && s < plain.length) underlineRanges.push([s, plain.length]);
      i += 4;
    } else if (rest.startsWith('**')) {
      if (boldStack.length > 0) {
        const s = boldStack.pop()!;
        if (s < plain.length) boldRanges.push([s, plain.length]);
      } else {
        boldStack.push(plain.length);
      }
      i += 2;
    } else if (rest.startsWith('__')) {
      if (underlineStack.length > 0) {
        const s = underlineStack.pop()!;
        if (s < plain.length) underlineRanges.push([s, plain.length]);
      } else {
        underlineStack.push(plain.length);
      }
      i += 2;
    } else if (rest.startsWith('*') && !rest.startsWith('**')) {
      if (italicStack.length > 0) {
        const s = italicStack.pop()!;
        if (s < plain.length) italicRanges.push([s, plain.length]);
      } else {
        italicStack.push(plain.length);
      }
      i += 1;
    } else {
      plain += raw[i];
      i += 1;
    }
  }

  while (boldStack.length > 0) {
    const s = boldStack.pop()!;
    if (s < plain.length) boldRanges.push([s, plain.length]);
  }
  while (italicStack.length > 0) {
    const s = italicStack.pop()!;
    if (s < plain.length) italicRanges.push([s, plain.length]);
  }
  while (underlineStack.length > 0) {
    const s = underlineStack.pop()!;
    if (s < plain.length) underlineRanges.push([s, plain.length]);
  }

  if (!plain.length) return [];

  const boundaries = new Set<number>([0, plain.length]);
  for (const [s, e] of [...boldRanges, ...italicRanges, ...underlineRanges]) {
    boundaries.add(s);
    boundaries.add(e);
  }
  const sorted = Array.from(boundaries).sort((a, b) => a - b);

  const segments: TextSegment[] = [];
  for (let idx = 0; idx < sorted.length - 1; idx++) {
    const s = sorted[idx];
    const e = sorted[idx + 1];
    if (s >= e) continue;
    const mid = s;
    const bold = boldRanges.some(([rs, re]) => mid >= rs && mid < re);
    const italic = italicRanges.some(([rs, re]) => mid >= rs && mid < re);
    const underline = underlineRanges.some(([rs, re]) => mid >= rs && mid < re);
    segments.push({ text: plain.slice(s, e), bold, italic, underline });
  }
  return segments;
}

export function parseAndroidRichTextToSafeHtml(raw: string): string {
  const segments = parseAndroidRichTextToSegments(raw);
  if (!segments.length) return '';

  return segments
    .map((seg) => {
      let chunk = escapeHtml(seg.text).replace(/\r?\n/g, '<br>');
      if (seg.underline) chunk = `<u>${chunk}</u>`;
      if (seg.italic) chunk = `<i>${chunk}</i>`;
      if (seg.bold) chunk = `<b>${chunk}</b>`;
      return chunk;
    })
    .join('');
}

export function stripRichTagsToPlainText(raw: string): string {
  const segments = parseAndroidRichTextToSegments(raw);
  return segments.map((s) => s.text).join('');
}

/**
 * Walks a contentEditable DOM element and emits canonical Android <b>, <i>, <u> + \n text.
 */
export function serializeElementToAndroidRichText(root: HTMLElement): string {
  const chars: { ch: string; b: boolean; i: boolean; u: boolean }[] = [];

  function walk(node: Node, b: boolean, i: boolean, u: boolean, isTopLevel: boolean) {
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent || '';
      for (const ch of text) {
        if (ch !== '\u200B') {
          chars.push({ ch, b, i, u });
        }
      }
      return;
    }

    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const el = node as HTMLElement;
    const tag = el.tagName.toUpperCase();

    if (tag === 'BR') {
      chars.push({ ch: '\n', b: false, i: false, u: false });
      return;
    }

    const style = el.style;
    const nextB =
      b ||
      tag === 'B' ||
      tag === 'STRONG' ||
      style.fontWeight === 'bold' ||
      Number(style.fontWeight) >= 600;
    const nextI = i || tag === 'I' || tag === 'EM' || style.fontStyle === 'italic';
    const nextU = u || tag === 'U' || (style.textDecoration || '').includes('underline');

    const isBlock = tag === 'DIV' || tag === 'P' || tag === 'LI';
    if (isBlock && !isTopLevel && chars.length > 0 && chars[chars.length - 1].ch !== '\n') {
      chars.push({ ch: '\n', b: false, i: false, u: false });
    }

    // Handle <div><br></div> empty line case cleanly
    const children = Array.from(el.childNodes);
    if (isBlock && children.length === 1 && children[0].nodeName === 'BR') {
      if (chars.length === 0 || chars[chars.length - 1].ch !== '\n') {
        chars.push({ ch: '\n', b: false, i: false, u: false });
      }
      return;
    }

    for (const child of children) {
      walk(child, nextB, nextI, nextU, false);
    }

    if (isBlock && chars.length > 0 && chars[chars.length - 1].ch !== '\n') {
      chars.push({ ch: '\n', b: false, i: false, u: false });
    }
  }

  for (let idx = 0; idx < root.childNodes.length; idx++) {
    walk(root.childNodes[idx], false, false, false, idx === 0);
  }

  // Trim trailing automatic newline produced by block elements
  while (chars.length > 0 && chars[chars.length - 1].ch === '\n' && root.innerHTML.endsWith('</div>')) {
    // Keep intentional trailing newline only if there were multiple
    break;
  }

  let out = '';
  let curB = false;
  let curI = false;
  let curU = false;

  for (const item of chars) {
    const isNewline = item.ch === '\n';
    const targetB = isNewline ? false : item.b;
    const targetI = isNewline ? false : item.i;
    const targetU = isNewline ? false : item.u;

    if (curU && !targetU) {
      out += '</u>';
      curU = false;
    }
    if (curI && !targetI) {
      out += '</i>';
      curI = false;
    }
    if (curB && !targetB) {
      out += '</b>';
      curB = false;
    }

    if (!curB && targetB) {
      out += '<b>';
      curB = true;
    }
    if (!curI && targetI) {
      out += '<i>';
      curI = true;
    }
    if (!curU && targetU) {
      out += '<u>';
      curU = true;
    }

    out += item.ch;
  }

  if (curU) out += '</u>';
  if (curI) out += '</i>';
  if (curB) out += '</b>';

  return out.replace(/\n+$/g, '').trimEnd();
}
