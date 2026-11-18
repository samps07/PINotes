import React, { useState, useRef, useEffect } from 'react';
import {
  CheckSquare,
  Pin,
  Palette,
  Plus,
  Image as ImageIcon,
  Paperclip,
  Sparkles,
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
import { formatFileSize, isImageFileOrAttachment } from '../services/notesService';
import { analyzeWithAi } from '../services/aiAnalysisService';

interface QuickCaptureBarProps {
  isDark: boolean;
  categories: string[];
  defaultCategory: string;
  onCreateNote: (
    draft: Partial<NoteDocument>,
    imageFiles?: File[],
    otherFiles?: File[]
  ) => Promise<void>;
  onEnsureCategory: (category: string) => Promise<void>;
  onOpenSettings: () => void;
  onNotify: (msg: string) => void;
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
  onEnsureCategory,
  onOpenSettings,
  onNotify,
}) => {
  const [expanded, setExpanded] = useState(false);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [isTaskList, setIsTaskList] = useState(false);
  const [isPinned, setIsPinned] = useState(false);
  const [colorIndex, setColorIndex] = useState(0);
  const [category, setCategory] = useState(defaultCategory || 'General');
  const [webUrl, setWebUrl] = useState<string | null>(null);
  const [aiImageUrl, setAiImageUrl] = useState<string | null>(null);
  const [autoTags, setAutoTags] = useState<string | null>(null);

  const [showAiBar, setShowAiBar] = useState(false);
  const [aiInput, setAiInput] = useState('');
  const [isAnalyzing, setIsAnalyzing] = useState(false);

  const [showColors, setShowColors] = useState(false);
  const [queuedImages, setQueuedImages] = useState<QueuedImage[]>([]);
  const [queuedFiles, setQueuedFiles] = useState<File[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const colors = getNoteColors(colorIndex, isDark);

  useEffect(() => {
    setCategory(defaultCategory || 'General');
  }, [defaultCategory]);

  const addFiles = (files: File[]) => {
    const imgs = files.filter((f) => isImageFileOrAttachment(f.name, f.type, ''));
    const others = files.filter((f) => !isImageFileOrAttachment(f.name, f.type, ''));

    if (imgs.length > 0) {
      const mapped = imgs.map((file) => ({
        file,
        previewUrl: URL.createObjectURL(file),
      }));
      setQueuedImages((prev) => [...prev, ...mapped]);
      setExpanded(true);
      setErrorMsg(null);
    }

    if (others.length > 0) {
      setQueuedFiles((prev) => [...prev, ...others]);
      setExpanded(true);
      setErrorMsg(null);
    }
  };

  const removeQueuedImage = (idx: number) => {
    setQueuedImages((prev) => {
      const target = prev[idx];
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const removeQueuedFile = (idx: number) => {
    setQueuedFiles((prev) => prev.filter((_, i) => i !== idx));
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
    setWebUrl(null);
    setAiImageUrl(null);
    setAutoTags(null);
    setShowAiBar(false);
    setAiInput('');
    setShowColors(false);
    setQueuedImages([]);
    setQueuedFiles([]);
    setErrorMsg(null);
  };

  const handleRunAiAnalysis = async () => {
    setErrorMsg(null);
    setIsAnalyzing(true);
    try {
      const result = await analyzeWithAi(aiInput, {
        existingTitle: title,
        existingContent: content,
        webUrl: webUrl || undefined,
      });

      if (result.title) setTitle(result.title);
      if (result.content) {
        setIsTaskList(false);
        setContent(result.content);
      }
      if (result.resolvedUrl) setWebUrl(result.resolvedUrl);
      if (result.imageUrl) setAiImageUrl(result.imageUrl);
      if (result.category) {
        setCategory(result.category);
        await onEnsureCategory(result.category);
      }
      if (result.tags) setAutoTags(result.tags);

      setShowAiBar(false);
      onNotify('AI Extraction Successful!');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'AI analysis failed.');
    } finally {
      setIsAnalyzing(false);
    }
  };

  const handleSave = async () => {
    if (
      !title.trim() &&
      !content.trim() &&
      queuedImages.length === 0 &&
      queuedFiles.length === 0 &&
      !aiImageUrl
    ) {
      reset();
      return;
    }
    setIsSaving(true);
    setErrorMsg(null);
    try {
      const fallbackTitle =
        queuedImages.length > 0 || aiImageUrl
          ? 'Image Note'
          : queuedFiles.length > 0
          ? queuedFiles[0].name
          : 'Untitled Note';

      await onCreateNote(
        {
          title: title.trim() || fallbackTitle,
          content,
          isTaskList,
          isPinned,
          colorIndex,
          category,
          webUrl,
          imageUrl: aiImageUrl,
          autoTags,
        },
        queuedImages.map((q) => q.file),
        queuedFiles
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
    imageInputRef.current?.click();
  };

  const openWithFile = () => {
    setExpanded(true);
    fileInputRef.current?.click();
  };

  const openWithAi = () => {
    setExpanded(true);
    setShowAiBar(true);
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

  const availableCategories = React.useMemo(() => {
    const list = [...categories];
    if (category && !list.some((c) => c.toLowerCase() === category.toLowerCase())) {
      list.push(category);
    }
    return list;
  }, [categories, category]);

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
        ref={imageInputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={(e) => {
          const files = Array.from(e.target.files || []);
          if (files.length > 0) addFiles(files);
          e.target.value = '';
        }}
      />
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => {
          const files = Array.from(e.target.files || []);
          if (files.length > 0) addFiles(files);
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
          <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              onClick={openWithAi}
              title="AI Curate Link / Topic"
              className="p-2 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
              style={{ color: 'var(--accent)' }}
            >
              <Sparkles className="w-4 h-4" />
            </button>
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
            <button
              type="button"
              onClick={openWithFile}
              title="New note with file attachment"
              className="p-2 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
            >
              <Paperclip className="w-4 h-4 opacity-75" />
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
          {/* AI Curate Bar */}
          {showAiBar && (
            <div
              className="rounded-2xl border p-3 space-y-2 bg-black/15"
              style={{ borderColor: 'var(--border-subtle)' }}
            >
              <div className="flex items-center justify-between text-xs font-bold">
                <span className="inline-flex items-center gap-1.5" style={{ color: 'var(--accent)' }}>
                  <Sparkles className="w-3.5 h-3.5" />
                  AI Curate URL or Text
                </span>
                <button
                  type="button"
                  onClick={onOpenSettings}
                  className="text-[11px] font-medium underline opacity-70 hover:opacity-100 cursor-pointer"
                >
                  API Key Settings
                </button>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={aiInput}
                  onChange={(e) => setAiInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      void handleRunAiAnalysis();
                    }
                  }}
                  placeholder="Paste URL, game/movie/book name, or text..."
                  className="flex-1 rounded-xl px-3 py-1.5 text-xs border bg-black/20 focus:outline-none"
                  style={{ borderColor: 'var(--border-subtle)' }}
                />
                <button
                  type="button"
                  disabled={isAnalyzing}
                  onClick={() => void handleRunAiAnalysis()}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold shrink-0 cursor-pointer disabled:opacity-60"
                  style={{
                    background: 'var(--accent)',
                    color: 'var(--accent-on)',
                  }}
                >
                  {isAnalyzing ? (
                    <>
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      Analyzing...
                    </>
                  ) : (
                    <>
                      <Sparkles className="w-3.5 h-3.5" />
                      Analyze
                    </>
                  )}
                </button>
              </div>
            </div>
          )}

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
              placeholder="Take a note... (or paste / drop images & files)"
              textColor={colors.text}
              minHeight="96px"
            />
          )}

          {/* AI Extracted Poster / Image Preview */}
          {aiImageUrl && (
            <div className="relative rounded-2xl overflow-hidden border max-w-xs aspect-[16/10] bg-black/25" style={{ borderColor: 'var(--border-subtle)' }}>
              <img src={aiImageUrl} alt="AI preview" className="w-full h-full object-cover" />
              <button
                type="button"
                onClick={() => setAiImageUrl(null)}
                title="Remove image"
                className="absolute top-1.5 right-1.5 p-1 rounded-full bg-black/75 text-white cursor-pointer"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
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

          {/* Queued File Attachments */}
          {queuedFiles.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {queuedFiles.map((file, idx) => (
                <div
                  key={idx}
                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-xs bg-black/20 border"
                  style={{ borderColor: 'var(--border-subtle)' }}
                >
                  <Paperclip className="w-3.5 h-3.5" style={{ color: 'var(--accent)' }} />
                  <span className="truncate max-w-[160px]">{file.name}</span>
                  <span className="opacity-55 text-[10px]">{formatFileSize(file.size)}</span>
                  <button
                    type="button"
                    onClick={() => removeQueuedFile(idx)}
                    className="opacity-70 hover:opacity-100 cursor-pointer"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* AI Auto-Tags Preview */}
          {autoTags && (
            <div className="flex flex-wrap items-center gap-1.5">
              {autoTags
                .split('|')
                .filter(Boolean)
                .map((t) => (
                  <span
                    key={t}
                    className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] bg-black/20"
                  >
                    <Sparkles className="w-2.5 h-2.5" />
                    {t}
                  </span>
                ))}
            </div>
          )}

          {errorMsg && (
            <div className="flex items-start justify-between gap-2 rounded-xl px-3 py-2 text-xs bg-rose-500/15 text-rose-300 border border-rose-500/30">
              <span className="flex items-start gap-2">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{errorMsg}</span>
              </span>
              {errorMsg.toLowerCase().includes('api key') && (
                <button
                  type="button"
                  onClick={onOpenSettings}
                  className="underline font-semibold shrink-0 cursor-pointer"
                >
                  Open Settings
                </button>
              )}
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
          <div
            className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setShowAiBar((v) => !v)}
                title="AI Analyze URL / Text"
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
                style={showAiBar ? { color: 'var(--accent)' } : {}}
              >
                <Sparkles className="w-4 h-4" />
              </button>

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
                onClick={() => imageInputRef.current?.click()}
                title="Add image"
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <ImageIcon className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                title="Attach file"
                className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <Paperclip className="w-4 h-4" />
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
                {availableCategories.map((cat) => (
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
