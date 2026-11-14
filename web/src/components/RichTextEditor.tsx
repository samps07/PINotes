import React, { useState, useRef, useEffect } from 'react';
import { Bold, Italic, Underline, RemoveFormatting } from 'lucide-react';
import {
  parseAndroidRichTextToSafeHtml,
  serializeElementToAndroidRichText,
} from '../utils/richText';

interface RichTextEditorProps {
  initialValue: string;
  onChange: (serialized: string) => void;
  placeholder?: string;
  textColor?: string;
  minHeight?: string;
}

export const RichTextEditor: React.FC<RichTextEditorProps> = ({
  initialValue,
  onChange,
  placeholder = 'Write something down...',
  textColor,
  minHeight = '140px',
}) => {
  const editorRef = useRef<HTMLDivElement>(null);
  const [activeFormats, setActiveFormats] = useState({
    bold: false,
    italic: false,
    underline: false,
  });

  useEffect(() => {
    if (editorRef.current) {
      editorRef.current.innerHTML = parseAndroidRichTextToSafeHtml(initialValue);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const syncValue = () => {
    if (!editorRef.current) return;
    onChange(serializeElementToAndroidRichText(editorRef.current));
    refreshToolbarState();
  };

  const refreshToolbarState = () => {
    try {
      setActiveFormats({
        bold: document.queryCommandState('bold'),
        italic: document.queryCommandState('italic'),
        underline: document.queryCommandState('underline'),
      });
    } catch {
      // ignore if document selection unavailable
    }
  };

  const execFormat = (command: 'bold' | 'italic' | 'underline' | 'removeFormat') => {
    editorRef.current?.focus();
    document.execCommand(command, false);
    syncValue();
  };

  return (
    <div className="space-y-2">
      {/* Editable Body */}
      <div
        ref={editorRef}
        contentEditable
        suppressContentEditableWarning
        data-placeholder={placeholder}
        onInput={syncValue}
        onKeyUp={refreshToolbarState}
        onMouseUp={refreshToolbarState}
        onBlur={syncValue}
        className="pinotes-editor pinotes-richtext w-full rounded-2xl px-3.5 py-3 text-sm leading-relaxed border focus:outline-none focus:ring-2 focus:ring-[#D0BCFF]/40 overflow-y-auto max-h-72 whitespace-pre-wrap"
        style={{
          minHeight,
          color: textColor,
          background: 'rgba(0,0,0,0.12)',
          borderColor: 'var(--border-subtle)',
        }}
      />

      {/* Text Formatting Toolbar below note content box & above attachments (parity with Android) */}
      <div
        className="inline-flex items-center gap-1.5 px-2 py-1 rounded-xl border"
        style={{
          background: 'rgba(0,0,0,0.16)',
          borderColor: 'var(--border-subtle)',
        }}
      >
        <button
          type="button"
          onMouseDown={(e) => {
            e.preventDefault();
            execFormat('bold');
          }}
          title="Bold (Ctrl+B)"
          className="w-8 h-8 rounded-full flex items-center justify-center transition-colors cursor-pointer"
          style={
            activeFormats.bold
              ? {
                  background: 'var(--accent-container)',
                  color: 'var(--accent-container-text)',
                }
              : {}
          }
        >
          <Bold className="w-4 h-4" />
        </button>

        <button
          type="button"
          onMouseDown={(e) => {
            e.preventDefault();
            execFormat('italic');
          }}
          title="Italic (Ctrl+I)"
          className="w-8 h-8 rounded-full flex items-center justify-center transition-colors cursor-pointer"
          style={
            activeFormats.italic
              ? {
                  background: 'var(--accent-container)',
                  color: 'var(--accent-container-text)',
                }
              : {}
          }
        >
          <Italic className="w-4 h-4" />
        </button>

        <button
          type="button"
          onMouseDown={(e) => {
            e.preventDefault();
            execFormat('underline');
          }}
          title="Underline (Ctrl+U)"
          className="w-8 h-8 rounded-full flex items-center justify-center transition-colors cursor-pointer"
          style={
            activeFormats.underline
              ? {
                  background: 'var(--accent-container)',
                  color: 'var(--accent-container-text)',
                }
              : {}
          }
        >
          <Underline className="w-4 h-4" />
        </button>

        <div className="w-px h-4 bg-white/15 mx-0.5" />

        <button
          type="button"
          onMouseDown={(e) => {
            e.preventDefault();
            execFormat('removeFormat');
          }}
          title="Clear formatting"
          className="w-8 h-8 rounded-full flex items-center justify-center opacity-70 hover:opacity-100 transition-opacity cursor-pointer"
        >
          <RemoveFormatting className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
