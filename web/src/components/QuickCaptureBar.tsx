import React, { useState, useRef, useEffect } from 'react';
import {
  CheckSquare,
  Pin,
  Palette,
  Plus,
  Image as ImageIcon,
  X,
  AlertCircle,
  Loader2,
} from 'lucide-react';
import {
  getNoteColors,
  NOTE_COLORS,
  NoteDocument,
} from '../types/note';
import { TableChecklistEditor } from './TableChecklistEditor';
import { RichTextEditor } from './RichTextEditor';

interface QuickCaptureBarProps {
  isDark: boolean;
  categories: string[];
  defaultCategory: string;
  onCreateNote: (draft: Partial<NoteDocument>, filesToUpload?: File[]) => Promise<void>;
}

interface QueuedImage {
  file: File;
  previewUrl: string;
}

export const QuickCaptureBar: React.FC<QuickCaptureBarProps> = ({
  isDark,
  categories,
  defaultCategory,
  onCreateNote,
}) => {
  const [expanded, setExpanded] = useState(false);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [isTaskList, setIsTaskList] = useState(false);
  const [isPinned, setIsPinned] = useState(false);
  const [colorIndex, setColorIndex] = useState(0);
  const [category, setCategory] = useState(defaultCategory || 'General');
  const [showColors, setShowColors] = useState(false);
  const [queuedImages, setQueuedImages] = useState<QueuedImage[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const colors = getNoteColors(colorIndex, isDark);

  useEffect(() => {
    setCategory(defaultCategory || 'General');
  }, [defaultCategory]);

  const addFiles = (files: File[]) => {
    const valid = files.filter((f) => f.type.startsWith('image/'));
    if (valid.length === 0) return;
    const mapped = valid.map((file) => ({
      file,
      previewUrl: URL.createObjectURL(file),
    }));
    setQueuedImages((prev) => [...prev, ...mapped]);
    setExpanded(true);
    setErrorMsg(null);
  };

  const removeQueuedImage = (idx: number) => {
    setQueuedImages((prev) => {
      const target = prev[idx];
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const reset = () => {
    queuedImages.forEach((item) => URL.revokeObjectURL(item.previewUrl));
    setExpanded(false);
    setTitle('');
    setContent('');
    setIsTaskList(false);
    setIsPinned(false);
    setColorIndex(0);
    setCategory(defaultCategory || 'General');
    setShowColors(false);
    setQueuedImages([]);
    setErrorMsg(null);
  };

  const handleSave = async () => {
    if (!title.trim() && !content.trim() && queuedImages.length === 0) {
      reset();
      return;
    }
    setIsSaving(true);
    setErrorMsg(null);
    try {
      await onCreateNote(
        {
          title: title.trim() || (queuedImages.length > 0 ? 'Image Note' : 'Untitled Note'),
          content,
          isTaskList,
          isPinned,
          colorIndex,
          category,
        },
        queuedImages.map((q) => q.file)
      );
      reset();
    } catch (err: unknown) {
      setErrorMsg(
        err instanceof Error ? err.message : 'Could not save note. Please try again.'
      );
    } finally {
      setIsSaving(false);
    }
  };

  const openChecklistMode = () => {
    setIsTaskList(true);
    setExpanded(true);
  };

  const openWithImage = () => {
    setExpanded(true);
    fileInputRef.current?.click();
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    const items = Array.from(e.clipboardData?.items || []);
    const pastedImages: File[] = [];
    for (const item of items) {
      if (item.type.startsWith('image/')) {
        const f = item.getAsFile();
        if (f) pastedImages.push(f);
      }
    }
    if (pastedImages.length > 0) {
      addFiles(pastedImages);
    }
  };

  return (
    <div
      className="max-w-2xl mx-auto mb-7"
      onPaste={handlePaste}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        addFiles(Array.from(e.dataTransfer.files || []));
      }}
    >
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={(e) => {
          const files = Array.from(e.target.files || []);
          if (files.length > 0) {
            addFiles(files);
          }
          e.target.value = '';
        }}
      />

      {!expanded ? (
        <div
          onClick={() => setExpanded(true)}
          className="flex items-center justify-between gap-3 rounded-2xl px-4 py-3 border shadow-md cursor-text transition-all hover:shadow-lg"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          <span className="text-sm font-medium opacity-60">Take a note...</span>
          <div
            className="flex items-center gap-1"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={openChecklistMode}
              title="New checklist or table note"
              className="p-2 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
            >
              <CheckSquare className="w-4 h-4 opacity-75" />
            </button>
            <button
              type="button"
              onClick={openWithImage}
              title="New note with image"
              className="p-2 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
            >
              <ImageIcon className="w-4 h-4 opacity-75" />
            </button>
          </div>
        </div>
      ) : (
        <div
          className="rounded-3xl p-4 sm:p-5 border shadow-xl space-y-3 transition-colors"
          style={{
            background: colors.bg,
            color: colors.text,
            borderColor: 'var(--border-subtle)',
          }}
        >
          {/* Title + Pin */}
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Title"
              autoFocus
              className="flex-1 bg-transparent text-base font-bold placeholder:opacity-50 focus:outline-none"
            />
            <button
              type="button"
              onClick={() => setIsPinned((v) => !v)}
              title={isPinned ? 'Unpin' : 'Pin note'}
              className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
            >
              <Pin
                className="w-4 h-4"
                style={isPinned ? { color: 'var(--accent)', fill: 'currentColor' } : { opacity: 0.6 }}
              />
            </button>
          </div>

          {/* Body */}
          {isTaskList ? (
            <TableChecklistEditor value={content} onChange={setContent} />
          ) : (
            <RichTextEditor
              initialValue={content}
              onChange={setContent}
              placeholder="Take a note... (or paste / drop images)"
              textColor={colors.text}
              minHeight="96px"
            />
          )}

          {/* Queued Image Visual Thumbnail Previews */}
          {queuedImages.length > 0 && (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 pt-1">
              {queuedImages.map((item, idx) => (
                <div
                  key={idx}
                  className="relative group rounded-2xl overflow-hidden border aspect-[4/3] bg-black/25"
                  style={{ borderColor: 'var(--border-subtle)' }}
                >
                  <img
                    src={item.previewUrl}
                    alt={item.file.name}
                    className="w-full h-full object-cover"
                  />
                  <button
                    type="button"
                    onClick={() => removeQueuedImage(idx)}
                    title="Remove image"
                    className="absolute top-1.5 right-1.5 p-1 rounded-full bg-black/75 text-white hover:bg-black cursor-pointer"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>
          )}

          {errorMsg && (
            <div className="flex items-start gap-2 rounded-xl px-3 py-2 text-xs bg-rose-500/15 text-rose-300 border border-rose-500/30">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <span>{errorMsg}</span>
            </div>
          )}

          {/* Color Palette Popover */}
          {showColors && (
            <div className="flex flex-wrap items-center gap-2 pt-1">
              {NOTE_COLORS.map((swatch) => {
                const bg = isDark ? swatch.darkBg : swatch.lightBg;
                return (
                  <button
                    key={swatch.index}
                    type="button"
                    onClick={() => setColorIndex(swatch.index)}
                    title={swatch.name}
                    className={`w-7 h-7 rounded-full border-2 transition-transform hover:scale-110 cursor-pointer ${
                      colorIndex === swatch.index
                        ? 'border-[#D0BCFF] scale-110'
                        : 'border-white/20'
                    }`}
                    style={{ background: bg }}
                  />
                );
              })}
            </div>
          )}

          {/* Footer Toolbar */}
          <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t" style={{ borderColor: 'var(--border-subtle)' }}>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setIsTaskList((v) => !v)}
                title={isTaskList ? 'Switch to text note' : 'Switch to checklist/table'}
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
                style={isTaskList ? { color: 'var(--accent)' } : {}}
              >
                <CheckSquare className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                title="Add image"
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <ImageIcon className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => setShowColors((v) => !v)}
                title="Note color"
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <Palette className="w-4 h-4" />
              </button>

              <select
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                className="ml-1 rounded-xl px-2.5 py-1 text-xs font-medium border bg-black/15 focus:outline-none cursor-pointer"
                style={{ borderColor: 'var(--border-subtle)' }}
              >
                {categories.map((cat) => (
                  <option key={cat} value={cat} className="text-black">
                    {cat}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={reset}
                disabled={isSaving}
                className="px-3 py-1.5 rounded-xl text-xs font-medium hover:bg-white/10 cursor-pointer"
              >
                Close
              </button>
              <button
                type="button"
                disabled={isSaving}
                onClick={handleSave}
                className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold cursor-pointer disabled:opacity-60"
                style={{
                  background: 'var(--accent)',
                  color: 'var(--accent-on)',
                }}
              >
                {isSaving ? (
                  <>
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    Saving...
                  </>
                ) : (
                  <>
                    <Plus className="w-3.5 h-3.5" />
                    Save Note
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
