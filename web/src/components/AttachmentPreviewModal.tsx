import React, { useEffect, useState } from 'react';
import { FirebaseStorage } from 'firebase/storage';
import { FirebaseApp } from 'firebase/app';
import {
  X,
  Download,
  ExternalLink,
  FileText,
  Paperclip,
  Loader2,
  AlertCircle,
} from 'lucide-react';
import {
  dataUrlToBlob,
  formatFileSize,
  isImageFileOrAttachment,
  isWebRenderableUrl,
  resolveStorageDownloadUrl,
  triggerAttachmentDownload,
} from '../services/notesService';

export interface PreviewTarget {
  fileName: string;
  mimeType: string;
  fileSize?: number;
  downloadUrl: string;
  storagePath?: string;
}

interface AttachmentPreviewModalProps {
  target: PreviewTarget | null;
  storage?: FirebaseStorage | null;
  firebaseApp?: FirebaseApp | null;
  onClose: () => void;
}

export const AttachmentPreviewModal: React.FC<AttachmentPreviewModalProps> = ({
  target,
  storage,
  firebaseApp,
  onClose,
}) => {
  const [resolvedUrl, setResolvedUrl] = useState<string>('');
  const [blobObjectUrl, setBlobObjectUrl] = useState<string | null>(null);
  const [textPreview, setTextPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!target) {
      setResolvedUrl('');
      setTextPreview(null);
      setErrorMsg(null);
      return;
    }

    let cancelled = false;
    let createdBlobUrl: string | null = null;

    (async () => {
      setLoading(true);
      setErrorMsg(null);
      setTextPreview(null);

      let url = isWebRenderableUrl(target.downloadUrl) ? target.downloadUrl : '';
      if (!url && target.storagePath && storage) {
        const fetched = await resolveStorageDownloadUrl(storage, target.storagePath, firebaseApp);
        if (fetched) url = fetched;
      }

      if (cancelled) return;

      if (!url) {
        setResolvedUrl('');
        setErrorMsg(
          'This file was attached on Android without a cloud Storage URL. Sync notes from Android with Firebase Storage enabled to view it on Web.'
        );
        setLoading(false);
        return;
      }

      setResolvedUrl(url);

      // Convert data: URLs to Blob object URLs so Chrome/Edge/Firefox allow PDF iframes & new-tab previews cleanly
      if (url.startsWith('data:')) {
        const blob = dataUrlToBlob(url);
        if (blob) {
          createdBlobUrl = URL.createObjectURL(blob);
          if (!cancelled) setBlobObjectUrl(createdBlobUrl);
        }
      } else {
        setBlobObjectUrl(null);
      }

      const lowerMime = (target.mimeType || '').toLowerCase();
      const lowerName = (target.fileName || '').toLowerCase();
      const isTextLike =
        lowerMime.startsWith('text/') ||
        lowerMime.includes('json') ||
        lowerMime.includes('xml') ||
        /\.(txt|md|json|csv|log|yml|yaml|xml|html|css|js|ts|kt)$/i.test(lowerName);

      if (isTextLike) {
        try {
          if (url.startsWith('data:')) {
            const blob = dataUrlToBlob(url);
            if (blob) {
              const txt = await blob.text();
              if (!cancelled) setTextPreview(txt.slice(0, 120000));
            }
          } else {
            const resp = await fetch(url);
            if (resp.ok) {
              const txt = await resp.text();
              if (!cancelled) setTextPreview(txt.slice(0, 120000));
            }
          }
        } catch {
          // If CORS prevents reading text inline, download/open buttons still work
        }
      }

      if (!cancelled) setLoading(false);
    })();

    return () => {
      cancelled = true;
      if (createdBlobUrl) {
        URL.revokeObjectURL(createdBlobUrl);
      }
    };
  }, [target, storage, firebaseApp]);

  if (!target) return null;

  const isImage = isImageFileOrAttachment(target.fileName, target.mimeType, resolvedUrl || target.downloadUrl);
  const lowerMime = (target.mimeType || '').toLowerCase();
  const lowerName = (target.fileName || '').toLowerCase();
  const isPdf = lowerMime === 'application/pdf' || lowerName.endsWith('.pdf');
  const isAudio = lowerMime.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac|flac)$/i.test(lowerName);
  const isVideo = lowerMime.startsWith('video/') || /\.(mp4|webm|mov|mkv)$/i.test(lowerName);

  const openUrl = blobObjectUrl || resolvedUrl;

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-[70] bg-black/90 backdrop-blur-sm flex flex-col items-center justify-center p-3 sm:p-6"
    >
      {/* Top Toolbar */}
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-4xl flex items-center justify-between gap-3 mb-3 px-4 py-2.5 rounded-2xl bg-white/10 border border-white/15 text-white"
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <Paperclip className="w-4 h-4 shrink-0 opacity-80" />
          <div className="min-w-0">
            <div className="text-xs sm:text-sm font-semibold truncate">
              {target.fileName || 'Attachment'}
            </div>
            {(target.fileSize || target.mimeType) && (
              <div className="text-[11px] opacity-65 truncate">
                {target.mimeType}
                {target.fileSize ? ` • ${formatFileSize(target.fileSize)}` : ''}
              </div>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1.5 shrink-0">
          {resolvedUrl && (
            <button
              type="button"
              onClick={() => {
                void triggerAttachmentDownload(
                  {
                    fileName: target.fileName,
                    downloadUrl: resolvedUrl,
                    storagePath: target.storagePath,
                  },
                  storage,
                  firebaseApp
                );
              }}
              title="Download attachment"
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold bg-white/15 hover:bg-white/25 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Download</span>
            </button>
          )}

          {openUrl && (
            <a
              href={openUrl}
              target="_blank"
              rel="noopener noreferrer"
              title="Open in new tab"
              className="p-2 rounded-full bg-white/10 hover:bg-white/20"
            >
              <ExternalLink className="w-4 h-4" />
            </a>
          )}

          <button
            type="button"
            onClick={onClose}
            title="Close preview"
            className="p-2 rounded-full bg-white/10 hover:bg-white/20 cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Preview Body */}
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-4xl flex-1 max-h-[82vh] flex items-center justify-center overflow-hidden rounded-2xl"
      >
        {loading ? (
          <div className="flex flex-col items-center gap-2 text-white/75">
            <Loader2 className="w-7 h-7 animate-spin" />
            <span className="text-xs">Loading attachment preview...</span>
          </div>
        ) : errorMsg ? (
          <div className="max-w-md rounded-2xl p-6 bg-white/10 border border-white/15 text-white text-center space-y-3">
            <AlertCircle className="w-8 h-8 text-amber-300 mx-auto" />
            <p className="text-xs leading-relaxed opacity-85">{errorMsg}</p>
          </div>
        ) : isImage && openUrl ? (
          <img
            src={openUrl}
            alt={target.fileName}
            className="max-w-full max-h-[80vh] rounded-2xl object-contain shadow-2xl"
          />
        ) : isPdf && openUrl ? (
          <iframe
            src={openUrl}
            title={target.fileName}
            className="w-full h-[78vh] rounded-2xl bg-white border border-white/15"
          />
        ) : isVideo && openUrl ? (
          <video
            src={openUrl}
            controls
            autoPlay
            className="max-w-full max-h-[78vh] rounded-2xl shadow-2xl"
          />
        ) : isAudio && openUrl ? (
          <div className="w-full max-w-md rounded-2xl p-6 bg-white/10 border border-white/15 text-white space-y-4">
            <div className="text-sm font-semibold truncate">{target.fileName}</div>
            <audio src={openUrl} controls className="w-full" />
          </div>
        ) : textPreview !== null ? (
          <pre className="w-full h-[76vh] overflow-auto rounded-2xl p-5 bg-[#16151a] text-slate-100 border border-white/15 text-xs font-mono leading-relaxed whitespace-pre-wrap break-words">
            {textPreview}
          </pre>
        ) : (
          <div className="max-w-md rounded-3xl p-7 bg-white/10 border border-white/15 text-white text-center space-y-4">
            <div className="w-14 h-14 rounded-2xl bg-white/15 flex items-center justify-center mx-auto">
              <FileText className="w-7 h-7" />
            </div>
            <div>
              <div className="text-sm font-bold break-all">{target.fileName}</div>
              <div className="text-xs opacity-70 mt-1">
                {target.mimeType}
                {target.fileSize ? ` • ${formatFileSize(target.fileSize)}` : ''}
              </div>
            </div>
            <button
              type="button"
              onClick={() => {
                void triggerAttachmentDownload(
                  {
                    fileName: target.fileName,
                    downloadUrl: resolvedUrl,
                    storagePath: target.storagePath,
                  },
                  storage,
                  firebaseApp
                );
              }}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-2xl text-xs font-bold bg-white text-black hover:bg-white/90 cursor-pointer"
            >
              <Download className="w-4 h-4" />
              Download File
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
