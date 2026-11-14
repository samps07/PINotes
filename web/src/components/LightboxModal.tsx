import React from 'react';
import { X, ExternalLink } from 'lucide-react';

interface LightboxModalProps {
  imageUrl: string | null;
  onClose: () => void;
}

export const LightboxModal: React.FC<LightboxModalProps> = ({ imageUrl, onClose }) => {
  if (!imageUrl) return null;

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-[60] bg-black/90 backdrop-blur-sm flex items-center justify-center p-4"
    >
      <div className="fixed top-4 right-4 flex items-center gap-2">
        <a
          href={imageUrl}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => e.stopPropagation()}
          title="Open original image"
          className="p-2.5 rounded-full bg-white/10 text-white hover:bg-white/20"
        >
          <ExternalLink className="w-5 h-5" />
        </a>
        <button
          type="button"
          onClick={onClose}
          title="Close preview"
          className="p-2.5 rounded-full bg-white/10 text-white hover:bg-white/20 cursor-pointer"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      <img
        src={imageUrl}
        alt="Full screen attachment"
        onClick={(e) => e.stopPropagation()}
        className="max-w-full max-h-[88vh] rounded-2xl object-contain shadow-2xl"
      />
    </div>
  );
};
