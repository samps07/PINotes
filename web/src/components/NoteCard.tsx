import React, { useState } from 'react';
import {
  Pin,
  Archive,
  ArchiveRestore,
  Trash2,
  RotateCcw,
  Palette,
  ExternalLink,
  Paperclip,
  Clock,
  CheckSquare,
  Square,
  Sparkles,
  Copy,
} from 'lucide-react';
import { getNoteColors, NOTE_COLORS, NoteDocument } from '../types/note';
import { parseAndroidRichTextToSafeHtml } from '../utils/richText';
import {
  formatNoteForCopying,
  parseTableListData,
  toggleTaskItemChecked,
} from '../utils/tableParser';
import { getNoteFileAttachments, getNoteImageUrls } from '../services/notesService';

interface NoteCardProps {
  note: NoteDocument;
  isDark: boolean;
  onOpen: () => void;
  onImageClick: (url: string) => void;
  onTogglePin: () => void;
  onChangeColor: (colorIndex: number) => void;
  onToggleArchive: () => void;
  onSoftDelete: () => void;
  onRestore: () => void;
  onPermanentDelete: () => void;
  onUpdateContent: (newContent: string) => void;
  onNotify: (msg: string) => void;
}

export const NoteCard: React.FC<NoteCardProps> = ({
  note,
  isDark,
  onOpen,
  onImageClick,
  onTogglePin,
  onChangeColor,
  onToggleArchive,
  onSoftDelete,
  onRestore,
  onPermanentDelete,
  onUpdateContent,
  onNotify,
}) => {
  const [showColorMenu, setShowColorMenu] = useState(false);
  const colors = getNoteColors(note.colorIndex, isDark);
  const images = getNoteImageUrls(note);
  const files = getNoteFileAttachments(note);
  const manualTags = note.tags
    ? note.tags.split('|').map((t) => t.trim()).filter(Boolean)
    : [];
  const autoTags = note.autoTags
    ? note.autoTags.split('|').map((t) => t.trim()).filter(Boolean)
    : [];

  const isTrashed = note.deletedAt !== null;

  const formatScheduleTime = (ms: number) => {
    return new Date(ms).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  };

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(formatNoteForCopying(note));
      onNotify('Note copied to clipboard');
    } catch {
      onNotify('Could not copy note');
    }
  };

  return (
    <article
      onClick={onOpen}
      className="group relative rounded-2xl border overflow-visible transition-all duration-150 hover:shadow-xl cursor-pointer flex flex-col"
      style={{
        background: colors.bg,
        color: colors.text,
        borderColor: 'var(--border-subtle)',
      }}
    >
      {/* Image Gallery Preview */}
      {images.length > 0 && (
        <div
          className={`grid gap-1 p-1.5 pb-0 ${
            images.length === 1 ? 'grid-cols-1' : 'grid-cols-2'
          }`}
          onClick={(e) => e.stopPropagation()}
        >
          {images.slice(0, 4).map((url, idx) => (
            <button
              key={idx}
              type="button"
              onClick={() => onImageClick(url)}
              className="relative overflow-hidden rounded-xl bg-black/20 aspect-[16/10] cursor-zoom-in"
            >
              <img
                src={url}
                alt={`Attachment ${idx + 1}`}
                loading="lazy"
                className="w-full h-full object-cover transition-transform duration-200 hover:scale-105"
              />
              {idx === 3 && images.length > 4 && (
                <div className="absolute inset-0 bg-black/60 flex items-center justify-center text-white text-sm font-bold">
                  +{images.length - 4}
                </div>
              )}
            </button>
          ))}
        </div>
      )}

      {/* Content Area */}
      <div className="p-4 space-y-2.5">
        {/* Title + Pin */}
        <div className="flex items-start justify-between gap-2">
          <h3 className="font-display font-bold text-base leading-snug break-words">
            {note.title || 'Untitled Note'}
          </h3>
          {!isTrashed && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onTogglePin();
              }}
              title={note.isPinned ? 'Unpin note' : 'Pin note'}
              className={`p-1.5 rounded-full hover:bg-white/10 transition-opacity shrink-0 cursor-pointer ${
                note.isPinned ? 'opacity-100' : 'opacity-0 group-hover:opacity-75'
              }`}
            >
              <Pin
                className="w-4 h-4"
                style={
                  note.isPinned
                    ? { color: 'var(--accent)', fill: 'currentColor' }
                    : {}
                }
              />
            </button>
          )}
        </div>

        {/* Body or Checklist/Table */}
        {note.isTaskList ? (
          (() => {
            const table = parseTableListData(note.content);
            const isMultiCol = table.headers.length > 1;
            const visibleItems = table.items.slice(0, 7);
            return (
              <div className="space-y-1.5 text-sm">
                {isMultiCol && (
                  <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-wider opacity-65 pl-6 border-b pb-1" style={{ borderColor: 'var(--border-subtle)' }}>
                    {table.headers.map((h, i) => (
                      <span key={i} className="flex-1 truncate">
                        {h}
                      </span>
                    ))}
                  </div>
                )}
                {visibleItems.map((item, idx) => (
                  <div
                    key={idx}
                    onClick={(e) => {
                      e.stopPropagation();
                      onUpdateContent(toggleTaskItemChecked(note.content, idx));
                    }}
                    className="flex items-start gap-2 rounded-lg px-1 py-0.5 hover:bg-white/10 transition-colors cursor-pointer"
                  >
                    {item.isChecked ? (
                      <CheckSquare
                        className="w-4 h-4 mt-0.5 shrink-0"
                        style={{ color: 'var(--accent)' }}
                      />
                    ) : (
                      <Square className="w-4 h-4 mt-0.5 shrink-0 opacity-60" />
                    )}
                    <div
                      className={`flex-1 flex items-center gap-2 min-w-0 ${
                        item.isChecked ? 'line-through opacity-55' : ''
                      }`}
                    >
                      {item.values.map((val, cIdx) => (
                        <span key={cIdx} className="flex-1 truncate text-xs sm:text-sm">
                          {val}
                        </span>
                      ))}
                    </div>
                  </div>
                ))}
                {table.items.length > 7 && (
                  <div className="text-xs opacity-60 pl-6">
                    +{table.items.length - 7} more items
                  </div>
                )}
              </div>
            );
          })()
        ) : (
          note.content && (
            <div
              className="pinotes-richtext text-sm opacity-90 leading-relaxed line-clamp-8 break-words whitespace-pre-wrap"
              dangerouslySetInnerHTML={{
                __html: parseAndroidRichTextToSafeHtml(note.content),
              }}
            />
          )
        )}

        {/* Website URL Chip */}
        {note.webUrl && (
          <a
            href={note.webUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => e.stopPropagation()}
            className="inline-flex items-center gap-1.5 max-w-full px-2.5 py-1 rounded-xl text-xs font-medium bg-black/20 hover:bg-black/30 border transition-colors"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            <ExternalLink className="w-3 h-3 shrink-0" style={{ color: 'var(--accent)' }} />
            <span className="truncate">{note.webUrl.replace(/^https?:\/\//, '')}</span>
          </a>
        )}

        {/* File Attachments */}
        {files.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {files.map((att, idx) => (
              <a
                key={idx}
                href={att.downloadUrl}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[11px] bg-black/20 hover:bg-black/30 border max-w-full"
                style={{ borderColor: 'var(--border-subtle)' }}
              >
                <Paperclip className="w-3 h-3 shrink-0" />
                <span className="truncate max-w-[150px]">{att.fileName}</span>
              </a>
            ))}
          </div>
        )}

        {/* Category + Tags */}
        <div className="flex flex-wrap items-center gap-1.5 pt-1">
          {note.category && note.category !== 'General' && (
            <span
              className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-black/20 border"
              style={{ borderColor: 'var(--border-subtle)' }}
            >
              {note.category}
            </span>
          )}
          {manualTags.map((tag) => (
            <span
              key={tag}
              className="px-2 py-0.5 rounded-full text-[11px] font-medium"
              style={{
                background: 'var(--accent-container)',
                color: 'var(--accent-container-text)',
              }}
            >
              #{tag}
            </span>
          ))}
          {autoTags.map((tag) => (
            <span
              key={`auto-${tag}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-black/20 opacity-85"
            >
              <Sparkles className="w-2.5 h-2.5" />
              {tag}
            </span>
          ))}
        </div>

        {/* Scheduled Notification Tag */}
        {note.isScheduled && note.scheduledTime && (
          <div className="flex items-center gap-1.5 text-[11px] font-semibold pt-0.5" style={{ color: 'var(--accent)' }}>
            <Clock className="w-3.5 h-3.5 shrink-0" />
            <span className="truncate">
              Upcoming: {formatScheduleTime(note.scheduledTime)}
              {note.repeatFrequency && note.repeatFrequency !== 'once'
                ? ` • ${
                    note.repeatFrequency === 'minutes'
                      ? `Every ${note.repeatIntervalMinutes || 15}m`
                      : note.repeatFrequency
                  }`
                : ''}
            </span>
          </div>
        )}
      </div>

      {/* Hover Footer Toolbar */}
      <div
        className="px-2.5 pb-2 pt-0.5 flex items-center justify-between opacity-100 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity"
        onClick={(e) => e.stopPropagation()}
      >
        {isTrashed ? (
          <div className="flex items-center gap-1 w-full justify-end">
            <button
              type="button"
              onClick={onRestore}
              title="Restore note"
              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-xl text-xs font-medium hover:bg-white/10 cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              Restore
            </button>
            <button
              type="button"
              onClick={onPermanentDelete}
              title="Delete permanently"
              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-xl text-xs font-medium text-rose-400 hover:bg-rose-500/15 cursor-pointer"
            >
              <Trash2 className="w-3.5 h-3.5" />
              Delete Forever
            </button>
          </div>
        ) : (
          <>
            <div className="flex items-center gap-0.5 relative">
              <button
                type="button"
                onClick={() => setShowColorMenu((v) => !v)}
                title="Change color"
                className="p-1.5 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <Palette className="w-4 h-4 opacity-75" />
              </button>

              {showColorMenu && (
                <div
                  className="absolute bottom-9 left-0 z-20 flex items-center gap-1.5 p-2 rounded-2xl border shadow-2xl"
                  style={{
                    background: 'var(--bg-surface)',
                    borderColor: 'var(--border-subtle)',
                  }}
                >
                  {NOTE_COLORS.map((sw) => (
                    <button
                      key={sw.index}
                      type="button"
                      title={sw.name}
                      onClick={() => {
                        onChangeColor(sw.index);
                        setShowColorMenu(false);
                      }}
                      className={`w-6 h-6 rounded-full border-2 cursor-pointer ${
                        note.colorIndex === sw.index
                          ? 'border-[#D0BCFF] scale-110'
                          : 'border-white/20'
                      }`}
                      style={{ background: isDark ? sw.darkBg : sw.lightBg }}
                    />
                  ))}
                </div>
              )}

              <button
                type="button"
                onClick={handleCopy}
                title="Copy note text"
                className="p-1.5 rounded-full hover:bg-white/10 cursor-pointer"
              >
                <Copy className="w-4 h-4 opacity-75" />
              </button>

              <button
                type="button"
                onClick={onToggleArchive}
                title={note.isArchived ? 'Unarchive' : 'Archive'}
                className="p-1.5 rounded-full hover:bg-white/10 cursor-pointer"
              >
                {note.isArchived ? (
                  <ArchiveRestore className="w-4 h-4 opacity-75" />
                ) : (
                  <Archive className="w-4 h-4 opacity-75" />
                )}
              </button>

              <button
                type="button"
                onClick={onSoftDelete}
                title="Move to trash"
                className="p-1.5 rounded-full hover:bg-white/10 hover:text-rose-400 cursor-pointer"
              >
                <Trash2 className="w-4 h-4 opacity-75" />
              </button>
            </div>

            <span className="text-[10px] opacity-50 pr-1">
              {new Date(note.updatedAt || note.timestamp).toLocaleDateString(undefined, {
                month: 'short',
                day: 'numeric',
              })}
            </span>
          </>
        )}
      </div>
    </article>
  );
};
