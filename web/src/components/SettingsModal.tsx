import React, { useState } from 'react';
import {
  X,
  LogOut,
  RefreshCw,
  Copy,
  Check,
  Cloud,
  User,
  Trash2,
} from 'lucide-react';
import { FirebaseUserConfig } from '../types/note';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  config: FirebaseUserConfig;
  userEmail: string | null;
  userUid: string | null;
  onManualSync: () => Promise<void>;
  onSignOut: () => Promise<void>;
  onForgetConfigAndSignOut: () => Promise<void>;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  isOpen,
  onClose,
  config,
  userEmail,
  userUid,
  onManualSync,
  onSignOut,
  onForgetConfigAndSignOut,
}) => {
  const [copied, setCopied] = useState(false);
  const [syncing, setSyncing] = useState(false);

  if (!isOpen) return null;

  const copyConfigJson = async () => {
    await navigator.clipboard.writeText(JSON.stringify(config, null, 2));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-sm"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-3xl border shadow-2xl p-6 space-y-5"
        style={{
          background: 'var(--bg-surface)',
          borderColor: 'var(--border-subtle)',
        }}
      >
        <div className="flex items-center justify-between">
          <h2 className="font-display text-lg font-bold">Cloud Sync & Account</h2>
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-full hover:bg-white/10 cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Account Info */}
        <div
          className="rounded-2xl p-4 border space-y-2"
          style={{
            background: 'var(--bg-canvas)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          <div className="flex items-center gap-2 text-xs font-semibold opacity-70">
            <User className="w-3.5 h-3.5" />
            Signed-In Account
          </div>
          <div className="text-sm font-semibold break-all">{userEmail || 'Unknown'}</div>
          {userUid && (
            <div className="text-[11px] font-mono opacity-55 break-all">UID: {userUid}</div>
          )}
        </div>

        {/* Firebase Info */}
        <div
          className="rounded-2xl p-4 border space-y-2"
          style={{
            background: 'var(--bg-canvas)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-xs font-semibold opacity-70">
              <Cloud className="w-3.5 h-3.5" style={{ color: 'var(--accent)' }} />
              Connected Firebase Project
            </div>
            <button
              type="button"
              onClick={copyConfigJson}
              className="inline-flex items-center gap-1 text-xs font-medium px-2 py-1 rounded-lg hover:bg-white/10 cursor-pointer"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              {copied ? 'Copied' : 'Copy JSON'}
            </button>
          </div>
          <div className="text-sm font-semibold">{config.projectId}</div>
          <div className="text-[11px] opacity-65 break-all">
            Bucket: {config.storageBucket}
          </div>
        </div>

        {/* Actions */}
        <div className="space-y-2">
          <button
            type="button"
            disabled={syncing}
            onClick={async () => {
              setSyncing(true);
              try {
                await onManualSync();
              } finally {
                setSyncing(false);
              }
            }}
            className="w-full py-2.5 rounded-2xl text-sm font-semibold flex items-center justify-center gap-2 cursor-pointer"
            style={{
              background: 'var(--accent-container)',
              color: 'var(--accent-container-text)',
            }}
          >
            <RefreshCw className={`w-4 h-4 ${syncing ? 'animate-spin' : ''}`} />
            {syncing ? 'Refreshing Notes...' : 'Sync Now'}
          </button>

          <button
            type="button"
            onClick={onSignOut}
            className="w-full py-2.5 rounded-2xl text-sm font-medium border flex items-center justify-center gap-2 hover:bg-white/5 cursor-pointer"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            <LogOut className="w-4 h-4" />
            Sign Out (Keep Firebase Config)
          </button>

          <button
            type="button"
            onClick={onForgetConfigAndSignOut}
            className="w-full py-2.5 rounded-2xl text-sm font-medium text-rose-400 border border-rose-500/30 hover:bg-rose-500/10 flex items-center justify-center gap-2 cursor-pointer"
          >
            <Trash2 className="w-4 h-4" />
            Sign Out & Clear Saved Firebase Config
          </button>
        </div>
      </div>
    </div>
  );
};
