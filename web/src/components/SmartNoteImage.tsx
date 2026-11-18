import React, { useEffect, useState } from 'react';
import { FirebaseStorage } from 'firebase/storage';
import { FirebaseApp } from 'firebase/app';
import { ImageOff, Loader2, RefreshCw } from 'lucide-react';
import {
  isWebRenderableUrl,
  resolveStorageDownloadUrl,
} from '../services/notesService';

interface SmartNoteImageProps {
  src: string;
  storagePath?: string;
  alt: string;
  storage?: FirebaseStorage | null;
  firebaseApp?: FirebaseApp | null;
  isLocalOnlyUri?: boolean;
  className?: string;
  onResolvedUrl?: (url: string) => void;
  onClick?: (effectiveUrl: string) => void;
}

export const SmartNoteImage: React.FC<SmartNoteImageProps> = ({
  src,
  storagePath,
  alt,
  storage,
  firebaseApp,
  isLocalOnlyUri = false,
  className = 'w-full h-full object-cover',
  onResolvedUrl,
  onClick,
}) => {
  const [effectiveUrl, setEffectiveUrl] = useState<string>(() =>
    isWebRenderableUrl(src) ? src : ''
  );
  const [resolving, setResolving] = useState<boolean>(
    () => !isWebRenderableUrl(src) && Boolean(storagePath)
  );
  const [failed, setFailed] = useState<boolean>(false);
  const [triedStorageRecovery, setTriedStorageRecovery] = useState<boolean>(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    setTriedStorageRecovery(false);

    if (isWebRenderableUrl(src)) {
      setEffectiveUrl(src);
      setResolving(false);
      return;
    }

    setEffectiveUrl('');
    if (storagePath && storage) {
      setResolving(true);
      resolveStorageDownloadUrl(storage, storagePath, firebaseApp)
        .then((url) => {
          if (cancelled) return;
          if (url) {
            setEffectiveUrl(url);
            onResolvedUrl?.(url);
          } else {
            setFailed(true);
          }
        })
        .catch(() => {
          if (!cancelled) setFailed(true);
        })
        .finally(() => {
          if (!cancelled) setResolving(false);
        });
    } else {
      setResolving(false);
      setFailed(true);
    }

    return () => {
      cancelled = true;
    };
  }, [src, storagePath, storage, firebaseApp, onResolvedUrl]);

  const handleImageError = async () => {
    if (!triedStorageRecovery && storagePath && storage) {
      setTriedStorageRecovery(true);
      setResolving(true);
      const recovered = await resolveStorageDownloadUrl(storage, storagePath, firebaseApp);
      setResolving(false);
      if (recovered && recovered !== effectiveUrl) {
        setEffectiveUrl(recovered);
        setFailed(false);
        onResolvedUrl?.(recovered);
        return;
      }
    }
    setFailed(true);
  };

  const handleRetry = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setFailed(false);
    if (storagePath && storage) {
      setResolving(true);
      const recovered = await resolveStorageDownloadUrl(storage, storagePath, firebaseApp);
      setResolving(false);
      if (recovered) {
        setEffectiveUrl(recovered);
        return;
      }
    }
    if (isWebRenderableUrl(src)) {
      setEffectiveUrl(src);
    } else {
      setFailed(true);
    }
  };

  if (resolving) {
    return (
      <div className="w-full h-full flex items-center justify-center bg-black/20">
        <Loader2 className="w-5 h-5 animate-spin opacity-60" />
      </div>
    );
  }

  if (failed || !effectiveUrl) {
    return (
      <div className="w-full h-full flex flex-col items-center justify-center gap-1.5 p-2 text-center bg-black/25 opacity-75">
        <ImageOff className="w-5 h-5 opacity-60" />
        <span className="text-[10px] leading-tight line-clamp-2">
          {isLocalOnlyUri ? 'Stored locally on Android' : 'Image preview unavailable'}
        </span>
        {storagePath && (
          <button
            type="button"
            onClick={handleRetry}
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] bg-white/10 hover:bg-white/20 cursor-pointer"
          >
            <RefreshCw className="w-2.5 h-2.5" />
            Retry
          </button>
        )}
      </div>
    );
  }

  return (
    <img
      src={effectiveUrl}
      alt={alt}
      loading="lazy"
      onError={() => {
        void handleImageError();
      }}
      onClick={onClick ? () => onClick(effectiveUrl) : undefined}
      className={className}
    />
  );
};
