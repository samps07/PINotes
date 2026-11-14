import React, { useState, useRef } from 'react';
import {
  X,
  Pin,
  Archive,
  ArchiveRestore,
  Trash2,
  Image as ImageIcon,
  Paperclip,
  CheckSquare,
  FileText,
  Link2,
  Clock,
  Copy,
  Loader2,
  ExternalLink,
  Sparkles,
} from 'lucide-react';
import {
  getNoteColors,
  NOTE_COLORS,
  NoteDocument,
  SyncAttachment,
} from '../types/note';
import { RichTextEditor } from './RichTextEditor';
import { TableChecklistEditor } from './TableChecklistEditor';
import {
  getNoteFileAttachments,
  getNoteImageUrls,
} from '../services/notesService';
import { formatNoteForCopying } from '../utils/tableParser';

interface NoteEditorModalProps {
  note: NoteDocument;
  isDark: boolean;
  categories: string[];
  tags: string[];
  onClose: () => void;
  onSave: (updated: NoteDocument) => Promise<void>;
  onUploadImage: (noteId: string, file: File) => Promise<{
    downloadUrl: string;
    storagePath: string;
    attachment: SyncAttachment;
  }>;
  onUploadFile: (noteId: string, file: File) => Promise<SyncAttachment>;
  onDeleteStoragePath: (path: string) => Promise<void>;
  onToggleArchive: (note: NoteDocument) => Promise<void>;
  onSoftDelete: (note: NoteDocument) => Promise<void>;
  onImageClick: (url: string) => void;
  onNotify: (msg: string) => void;
}

export const NoteEditorModal: React.FC<NoteEditorModalProps> = ({
  note,
  isDark,
  categories,
  tags,
  onClose,
  onSave,
  onUploadImage,
  onUploadFile,
  onDeleteStoragePath,
  onToggleArchive,
  onSoftDelete,
  onImageClick,
  onNotify,
}) => {
  const [title, setTitle] = useState(note.title);
  const [content, setContent] = useState(note.content);
  const [isTaskList, setIsTaskList] = useState(note.isTaskList);
  const [isPinned, setIsPinned] = useState(note.isPinned);
  const [colorIndex, setColorIndex] = useState(note.colorIndex);
  const [category, setCategory] = useState(note.category || 'General');
  const [selectedTags, setSelectedTags] = useState<string[]>(() =>
    note.tags ? note.tags.split('|').map((t) => t.trim()).filter(Boolean) : []
  );
  const [webUrl, setWebUrl] = useState(note.webUrl || '');
  const [attachments, setAttachments] = useState<SyncAttachment[]>(note.attachments || []);
  const [imageUrl, setImageUrl] = useState<string | null>(note.imageUrl);
  const [imageStoragePath, setImageStoragePath] = useState<string | null>(
    note.imageStoragePath
  );

  // Schedule state
  const [isScheduled, setIsScheduled] = useState(note.isScheduled);
  const [scheduledTime, setScheduledTime] = useState<number | null>(note.scheduledTime);
  const [repeatFrequency, setRepeatFrequency] = useState<string>(
    note.repeatFrequency || 'once'
  );
  const [repeatIntervalMinutes, setRepeatIntervalMinutes] = useState<number>(
    note.repeatIntervalMinutes || 15
  );
  const [showSchedulePanel, setShowSchedulePanel] = useState(note.isScheduled);

  const [isUploading, setIsUploading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  const imageInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const colors = getNoteColors(colorIndex, isDark);

  const draftNote: NoteDocument = {
    ...note,
    title: title.trim() || 'Untitled Note',
    content,
    isTaskList,
    isPinned,
    colorIndex,
    category,
    tags: selectedTags.length > 0 ? selectedTags.join('|') : null,
    webUrl: webUrl.trim() ? webUrl.trim() : null,
    attachments,
    imageUrl,
    imageStoragePath,
    isScheduled,
    scheduledTime: isScheduled ? scheduledTime : null,
    repeatFrequency: isScheduled ? repeatFrequency : null,
    repeatIntervalMinutes:
      isScheduled && repeatFrequency === 'minutes' ? repeatIntervalMinutes : null,
    updatedAt: Date.now(),
  };

  const previewImages = getNoteImageUrls(draftNote);
  const previewFiles = getNoteFileAttachments(draftNote);
  const autoTags = note.autoTags
    ? note.autoTags.split('|').map((t) => t.trim()).filter(Boolean)
    : [];

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await onSave(draftNote);
      onClose();
    } finally {
      setIsSaving(false);
    }
  };

  const handleImageUpload = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setIsUploading(true);
    try {
      let nextAttachments = [...attachments];
      const urls = imageUrl ? imageUrl.split('|').filter(Boolean) : [];
      const paths = imageStoragePath ? imageStoragePath.split('|').filter(Boolean) : [];

      for (const file of Array.from(files)) {
        const res = await onUploadImage(note.noteId, file);
        nextAttachments.push(res.attachment);
        if (!urls.includes(res.downloadUrl)) urls.push(res.downloadUrl);
        if (!paths.includes(res.storagePath)) paths.push(res.storagePath);
      }

      setAttachments(nextAttachments);
      setImageUrl(urls.length > 0 ? urls.join('|') : null);
      setImageStoragePath(paths.length > 0 ? paths.join('|') : null);
      onNotify('Image uploaded and synced');
    } catch (err: unknown) {
      onNotify(err instanceof Error ? err.message : 'Image upload failed');
    } finally {
      setIsUploading(false);
    }
  };

  const handleFileUpload = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setIsUploading(true);
    try {
      const uploaded: SyncAttachment[] = [];
      for (const file of Array.from(files)) {
        uploaded.push(await onUploadFile(note.noteId, file));
      }
      setAttachments((prev) => [...prev, ...uploaded]);
      onNotify('File attached');
    } catch (err: unknown) {
      onNotify(err instanceof Error ? err.message : 'File upload failed');
    } finally {
      setIsUploading(false);
    }
  };

  const handleRemoveImage = async (targetUrl: string) => {
    const urls = imageUrl ? imageUrl.split('|').filter(Boolean) : [];
    const paths = imageStoragePath ? imageStoragePath.split('|').filter(Boolean) : [];
    const idx = urls.indexOf(targetUrl);
    if (idx !== -1) {
      const removedPath = paths[idx];
      if (removedPath) await onDeleteStoragePath(removedPath);
      urls.splice(idx, 1);
      if (idx < paths.length) paths.splice(idx, 1);
    }

    const matchingAtt = attachments.find((a) => a.downloadUrl === targetUrl);
    if (matchingAtt?.storagePath) {
      await onDeleteStoragePath(matchingAtt.storagePath);
    }

    setAttachments((prev) => prev.filter((a) => a.downloadUrl !== targetUrl));
    setImageUrl(urls.length > 0 ? urls.join('|') : null);
    setImageStoragePath(paths.length > 0 ? paths.join('|') : null);
  };

  const handleRemoveFile = async (att: SyncAttachment) => {
    if (att.storagePath) await onDeleteStoragePath(att.storagePath);
    setAttachments((prev) => prev.filter((a) => a.downloadUrl !== att.downloadUrl));
  };

  const toggleTag = (tag: string) => {
    setSelectedTags((prev) =>
      prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]
    );
  };

  const toDatetimeLocalValue = (ms: number | null) => {
    if (!ms) return '';
    const d = new Date(ms);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
      d.getHours()
    )}:${pad(d.getMinutes())}`;
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-5 bg-black/65 backdrop-blur-sm"
      onClick={handleSave}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl max-h-[92vh] rounded-3xl border shadow-2xl flex flex-col overflow-hidden"
        style={{
          background: colors.bg,
          color: colors.text,
          borderColor: 'var(--border-subtle)',
        }}
      >
        {/* Top Header */}
        <div
          className="flex items-center justify-between px-5 py-3.5 border-b"
          style={{ borderColor: 'var(--border-subtle)' }}
        >
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setIsTaskList((v) => !v)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border hover:bg-white/10 cursor-pointer"
              style={{ borderColor: 'var(--border-subtle)' }}
            >
              {isTaskList ? (
                <>
                  <FileText className="w-3.5 h-3.5" />
                  Switch to Text
                </>
              ) : (
                <>
                  <CheckSquare className="w-3.5 h-3.5" />
                  Checklist / Table
                </>
              )}
            </button>

            <button
              type="button"
              onClick={async () => {
                await navigator.clipboard.writeText(formatNoteForCopying(draftNote));
                onNotify('Copied note to clipboard');
              }}
              title="Copy formatted note"
              className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
            >
              <Copy className="w-4 h-4 opacity-75" />
            </button>
          </div>

          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setIsPinned((v) => !v)}
              title={isPinned ? 'Unpin' : 'Pin'}
              className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
            >
              <Pin
                className="w-4 h-4"
                style={
                  isPinned ? { color: 'var(--accent)', fill: 'currentColor' } : { opacity: 0.7 }
                }
              />
            </button>

            <button
              type="button"
              onClick={async () => {
                await onToggleArchive(draftNote);
                onClose();
              }}
              title={note.isArchived ? 'Unarchive' : 'Archive'}
              className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
            >
              {note.isArchived ? (
                <ArchiveRestore className="w-4 h-4 opacity-75" />
              ) : (
                <Archive className="w-4 h-4 opacity-75" />
              )}
            </button>

            <button
              type="button"
              onClick={async () => {
                await onSoftDelete(draftNote);
                onClose();
              }}
              title="Move to trash"
              className="p-2 rounded-full hover:bg-white/10 hover:text-rose-400 cursor-pointer"
            >
              <Trash2 className="w-4 h-4 opacity-75" />
            </button>

            <button
              type="button"
              onClick={onClose}
              title="Discard & Close"
              className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Scrollable Editor Content */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {/* Title */}
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Note title"
            className="w-full bg-transparent font-display text-xl font-bold placeholder:opacity-45 focus:outline-none"
          />

          {/* Body or Table */}
          {isTaskList ? (
            <TableChecklistEditor value={content} onChange={setContent} />
          ) : (
            <RichTextEditor
              initialValue={content}
              onChange={setContent}
              textColor={colors.text}
              minHeight="160px"
            />
          )}

          {/* Images & File Attachments Section (placed below formatting toolbar) */}
          <div className="space-y-2.5 pt-1">
            <input
              ref={imageInputRef}
              type="file"
              accept="image/*"
              multiple
              className="hidden"
              onChange={(e) => {
                handleImageUpload(e.target.files);
                e.target.value = '';
              }}
            />
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                handleFileUpload(e.target.files);
                e.target.value = '';
              }}
            />

            {previewImages.length > 0 && (
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
                {previewImages.map((url, idx) => (
                  <div
                    key={idx}
                    className="relative group rounded-2xl overflow-hidden border aspect-[4/3] bg-black/25"
                    style={{ borderColor: 'var(--border-subtle)' }}
                  >
                    <img
                      src={url}
                      alt={`Attachment ${idx + 1}`}
                      onClick={() => onImageClick(url)}
                      className="w-full h-full object-cover cursor-zoom-in"
                    />
                    <button
                      type="button"
                      onClick={() => handleRemoveImage(url)}
                      title="Remove image"
                      className="absolute top-1.5 right-1.5 p-1 rounded-full bg-black/70 text-white opacity-85 hover:opacity-100 cursor-pointer"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}

            {previewFiles.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {previewFiles.map((att, idx) => (
                  <div
                    key={idx}
                    className="inline-flex items-center gap-2 px-3 py-1.5 rounded-xl text-xs border bg-black/20"
                    style={{ borderColor: 'var(--border-subtle)' }}
                  >
                    <Paperclip className="w-3.5 h-3.5" />
                    <a
                      href={att.downloadUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="underline max-w-[180px] truncate"
                    >
                      {att.fileName}
                    </a>
                    <button
                      type="button"
                      onClick={() => handleRemoveFile(att)}
                      className="opacity-65 hover:opacity-100 cursor-pointer"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                disabled={isUploading}
                onClick={() => imageInputRef.current?.click()}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium border hover:bg-white/10 cursor-pointer disabled:opacity-50"
                style={{ borderColor: 'var(--border-subtle)' }}
              >
                {isUploading ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <ImageIcon className="w-3.5 h-3.5" />
                )}
                Add Image
              </button>

              <button
                type="button"
                disabled={isUploading}
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium border hover:bg-white/10 cursor-pointer disabled:opacity-50"
                style={{ borderColor: 'var(--border-subtle)' }}
              >
                <Paperclip className="w-3.5 h-3.5" />
                Attach File
              </button>

              <button
                type="button"
                onClick={() => setShowSchedulePanel((v) => !v)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-medium border hover:bg-white/10 cursor-pointer"
                style={{
                  borderColor: 'var(--border-subtle)',
                  ...(isScheduled ? { color: 'var(--accent)' } : {}),
                }}
              >
                <Clock className="w-3.5 h-3.5" />
                {isScheduled ? 'Reminder Active' : 'Reminder'}
              </button>
            </div>
          </div>

          {/* Reminder / Schedule Card */}
          {showSchedulePanel && (
            <div
              className="rounded-2xl border p-3.5 space-y-2.5 bg-black/15"
              style={{ borderColor: 'var(--border-subtle)' }}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold flex items-center gap-1.5" style={{ color: 'var(--accent)' }}>
                  <Clock className="w-3.5 h-3.5" />
                  Android Notification Schedule
                </span>
                <label className="inline-flex items-center gap-1.5 text-xs cursor-pointer">
                  <input
                    type="checkbox"
                    checked={isScheduled}
                    onChange={(e) => {
                      const next = e.target.checked;
                      setIsScheduled(next);
                      if (next && !scheduledTime) {
                        setScheduledTime(Date.now() + 60 * 60 * 1000);
                      }
                    }}
                  />
                  Enabled
                </label>
              </div>

              {isScheduled && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  <div>
                    <label className="block text-[11px] opacity-65 mb-1">
                      Trigger Date & Time
                    </label>
                    <input
                      type="datetime-local"
                      value={toDatetimeLocalValue(scheduledTime)}
                      onChange={(e) => {
                        const ms = e.target.value
                          ? new Date(e.target.value).getTime()
                          : null;
                        setScheduledTime(ms);
                      }}
                      className="w-full rounded-xl px-2.5 py-1.5 text-xs border bg-black/20 focus:outline-none"
                      style={{ borderColor: 'var(--border-subtle)' }}
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] opacity-65 mb-1">
                      Repeat Frequency
                    </label>
                    <div className="flex gap-2">
                      <select
                        value={repeatFrequency}
                        onChange={(e) => setRepeatFrequency(e.target.value)}
                        className="flex-1 rounded-xl px-2.5 py-1.5 text-xs border bg-black/20 focus:outline-none"
                        style={{ borderColor: 'var(--border-subtle)' }}
                      >
                        <option value="once" className="text-black">Once</option>
                        <option value="minutes" className="text-black">Every N Minutes</option>
                        <option value="daily" className="text-black">Daily</option>
                        <option value="weekly" className="text-black">Weekly</option>
                        <option value="monthly" className="text-black">Monthly</option>
                      </select>
                      {repeatFrequency === 'minutes' && (
                        <input
                          type="number"
                          min={1}
                          value={repeatIntervalMinutes}
                          onChange={(e) =>
                            setRepeatIntervalMinutes(Math.max(1, Number(e.target.value) || 15))
                          }
                          className="w-16 rounded-xl px-2 py-1.5 text-xs border bg-black/20 focus:outline-none"
                          style={{ borderColor: 'var(--border-subtle)' }}
                        />
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Web URL */}
          <div className="flex items-center gap-2 rounded-xl px-3 py-2 border bg-black/10" style={{ borderColor: 'var(--border-subtle)' }}>
            <Link2 className="w-4 h-4 opacity-60 shrink-0" />
            <input
              type="url"
              value={webUrl}
              onChange={(e) => setWebUrl(e.target.value)}
              placeholder="Optional reference URL (https://...)"
              className="flex-1 bg-transparent text-xs focus:outline-none"
            />
            {webUrl.trim() && (
              <a
                href={webUrl.trim()}
                target="_blank"
                rel="noopener noreferrer"
                className="p-1 opacity-75 hover:opacity-100"
                title="Open URL"
              >
                <ExternalLink className="w-3.5 h-3.5" />
              </a>
            )}
          </div>

          {/* Category & Tags */}
          <div className="space-y-2.5 pt-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-semibold opacity-65">Category:</span>
              {categories.map((cat) => (
                <button
                  key={cat}
                  type="button"
                  onClick={() => setCategory(cat)}
                  className="px-2.5 py-1 rounded-full text-xs font-medium border transition-colors cursor-pointer"
                  style={
                    category === cat
                      ? {
                          background: 'var(--accent)',
                          color: 'var(--accent-on)',
                          borderColor: 'transparent',
                        }
                      : { borderColor: 'var(--border-subtle)', opacity: 0.75 }
                  }
                >
                  {cat}
                </button>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-xs font-semibold opacity-65 mr-1">Tags:</span>
              {tags.map((t) => {
                const active = selectedTags.includes(t);
                return (
                  <button
                    key={t}
                    type="button"
                    onClick={() => toggleTag(t)}
                    className="px-2.5 py-0.5 rounded-full text-xs font-medium border transition-colors cursor-pointer"
                    style={
                      active
                        ? {
                            background: 'var(--accent-container)',
                            color: 'var(--accent-container-text)',
                            borderColor: 'transparent',
                          }
                        : { borderColor: 'var(--border-subtle)', opacity: 0.7 }
                    }
                  >
                    #{t}
                  </button>
                );
              })}
              {autoTags.map((t) => (
                <span
                  key={`auto-${t}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-black/20 opacity-80"
                >
                  <Sparkles className="w-3 h-3" />
                  {t}
                </span>
              ))}
            </div>
          </div>

          {/* Color Palette */}
          <div className="flex flex-wrap items-center gap-2 pt-1">
            <span className="text-xs font-semibold opacity-65 mr-1">Color:</span>
            {NOTE_COLORS.map((sw) => (
              <button
                key={sw.index}
                type="button"
                title={sw.name}
                onClick={() => setColorIndex(sw.index)}
                className={`w-7 h-7 rounded-full border-2 transition-transform hover:scale-110 cursor-pointer ${
                  colorIndex === sw.index
                    ? 'border-[#D0BCFF] scale-110'
                    : 'border-white/25'
                }`}
                style={{ background: isDark ? sw.darkBg : sw.lightBg }}
              />
            ))}
          </div>
        </div>

        {/* Footer */}
        <div
          className="flex items-center justify-between px-5 py-3 border-t"
          style={{ borderColor: 'var(--border-subtle)' }}
        >
          <span className="text-xs opacity-60">
            Edited {new Date(note.updatedAt || note.timestamp).toLocaleString()}
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl text-xs font-medium hover:bg-white/10 cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={isSaving || isUploading}
              onClick={handleSave}
              className="px-5 py-2 rounded-xl text-xs font-bold cursor-pointer disabled:opacity-60"
              style={{
                background: 'var(--accent)',
                color: 'var(--accent-on)',
              }}
            >
              {isSaving ? 'Saving...' : 'Save Note'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
