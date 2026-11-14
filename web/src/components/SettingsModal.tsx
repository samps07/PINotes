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
  ShieldCheck,
  Lock,
  Link,
  AlertCircle,
} from 'lucide-react';
import { FirebaseUserConfig, WebPasscodeConfig } from '../types/note';
import { buildQuickLoginUrl } from '../utils/firebaseClient';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  config: FirebaseUserConfig;
  userEmail: string | null;
  userUid: string | null;
  webLock: WebPasscodeConfig;
  onSetWebPasscode: (newPasscode: string) => Promise<void>;
  onResetWebPasscode: (currentPasscode: string, newPasscode: string) => Promise<boolean>;
  onUnsetWebPasscode: (currentPasscode: string) => Promise<boolean>;
  onLockNow: () => void;
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
  webLock,
  onSetWebPasscode,
  onResetWebPasscode,
  onUnsetWebPasscode,
  onLockNow,
  onManualSync,
  onSignOut,
  onForgetConfigAndSignOut,
}) => {
  const [copiedJson, setCopiedJson] = useState(false);
  const [copiedQuickLink, setCopiedQuickLink] = useState(false);
  const [syncing, setSyncing] = useState(false);

  // Passcode Lock management state
  const [lockMode, setLockMode] = useState<'none' | 'reset' | 'unset'>('none');
  const [currentPasscode, setCurrentPasscode] = useState('');
  const [newPasscode, setNewPasscode] = useState('');
  const [confirmPasscode, setConfirmPasscode] = useState('');
  const [lockError, setLockError] = useState<string | null>(null);
  const [lockSuccess, setLockSuccess] = useState<string | null>(null);
  const [lockSaving, setLockSaving] = useState(false);

  if (!isOpen) return null;

  const resetLockForm = () => {
    setCurrentPasscode('');
    setNewPasscode('');
    setConfirmPasscode('');
    setLockError(null);
    setLockMode('none');
  };

  const copyConfigJson = async () => {
    await navigator.clipboard.writeText(JSON.stringify(config, null, 2));
    setCopiedJson(true);
    setTimeout(() => setCopiedJson(false), 2000);
  };

  const copyQuickLoginLink = async () => {
    const url = buildQuickLoginUrl(config);
    await navigator.clipboard.writeText(url);
    setCopiedQuickLink(true);
    setTimeout(() => setCopiedQuickLink(false), 2500);
  };

  const handleSetPasscode = async (e: React.FormEvent) => {
    e.preventDefault();
    setLockError(null);
    setLockSuccess(null);
    if (newPasscode.length < 4) {
      setLockError('Passcode must be at least 4 characters.');
      return;
    }
    if (newPasscode !== confirmPasscode) {
      setLockError('Passcodes do not match.');
      return;
    }
    setLockSaving(true);
    try {
      await onSetWebPasscode(newPasscode);
      resetLockForm();
      setLockSuccess('Web app passcode lock enabled for all sessions.');
    } catch (err: unknown) {
      setLockError(err instanceof Error ? err.message : 'Failed to save passcode.');
    } finally {
      setLockSaving(false);
    }
  };

  const handleResetPasscode = async (e: React.FormEvent) => {
    e.preventDefault();
    setLockError(null);
    setLockSuccess(null);
    if (!currentPasscode) {
      setLockError('Enter your current passcode.');
      return;
    }
    if (newPasscode.length < 4) {
      setLockError('New passcode must be at least 4 characters.');
      return;
    }
    if (newPasscode !== confirmPasscode) {
      setLockError('New passcodes do not match.');
      return;
    }
    setLockSaving(true);
    try {
      const ok = await onResetWebPasscode(currentPasscode, newPasscode);
      if (!ok) {
        setLockError('Current passcode is incorrect.');
      } else {
        resetLockForm();
        setLockSuccess('Web app passcode updated across all sessions.');
      }
    } finally {
      setLockSaving(false);
    }
  };

  const handleUnsetPasscode = async (e: React.FormEvent) => {
    e.preventDefault();
    setLockError(null);
    setLockSuccess(null);
    if (!currentPasscode) {
      setLockError('Enter your current passcode to remove lock.');
      return;
    }
    setLockSaving(true);
    try {
      const ok = await onUnsetWebPasscode(currentPasscode);
      if (!ok) {
        setLockError('Current passcode is incorrect.');
      } else {
        resetLockForm();
        setLockSuccess('Web app passcode lock removed.');
      }
    } finally {
      setLockSaving(false);
    }
  };

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-sm"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-3xl border shadow-2xl p-6 space-y-5"
        style={{
          background: 'var(--bg-surface)',
          borderColor: 'var(--border-subtle)',
        }}
      >
        <div className="flex items-center justify-between">
          <h2 className="font-display text-lg font-bold">Web App Settings</h2>
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
          className="rounded-2xl p-4 border space-y-1.5"
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

        {/* Web App Passcode Lock Section (Set, Reset, Unset) */}
        <div
          className="rounded-2xl p-4 border space-y-3"
          style={{
            background: 'var(--bg-canvas)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-xs font-semibold">
              <ShieldCheck className="w-4 h-4" style={{ color: 'var(--accent)' }} />
              <span>Web App Passcode Lock</span>
            </div>
            <span
              className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                webLock.enabled
                  ? 'bg-emerald-500/15 text-emerald-400'
                  : 'bg-white/10 opacity-65'
              }`}
            >
              {webLock.enabled ? 'ACTIVE' : 'OFF'}
            </span>
          </div>

          <p className="text-[11px] opacity-65 leading-relaxed">
            Applies to all web sessions (desktop &amp; phone) and auto-locks on every refresh.
            Separate from your Android category lock.
          </p>

          {lockSuccess && (
            <div className="text-xs text-emerald-400 bg-emerald-500/10 border border-emerald-500/25 rounded-xl px-3 py-2">
              {lockSuccess}
            </div>
          )}

          {lockError && (
            <div className="flex items-center gap-1.5 text-xs text-rose-300 bg-rose-500/15 border border-rose-500/30 rounded-xl px-3 py-2">
              <AlertCircle className="w-3.5 h-3.5 shrink-0" />
              <span>{lockError}</span>
            </div>
          )}

          {!webLock.enabled ? (
            <form onSubmit={handleSetPasscode} className="space-y-2 pt-1">
              <input
                type="password"
                value={newPasscode}
                onChange={(e) => setNewPasscode(e.target.value)}
                placeholder="Set passcode (min 4 chars)"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <input
                type="password"
                value={confirmPasscode}
                onChange={(e) => setConfirmPasscode(e.target.value)}
                placeholder="Confirm passcode"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <button
                type="submit"
                disabled={lockSaving}
                className="w-full py-2 rounded-xl text-xs font-bold cursor-pointer disabled:opacity-60"
                style={{
                  background: 'var(--accent)',
                  color: 'var(--accent-on)',
                }}
              >
                {lockSaving ? 'Saving...' : 'Set Passcode Lock'}
              </button>
            </form>
          ) : lockMode === 'none' ? (
            <div className="grid grid-cols-3 gap-2 pt-1">
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onLockNow();
                }}
                className="py-2 px-2 rounded-xl text-xs font-semibold flex items-center justify-center gap-1 cursor-pointer"
                style={{
                  background: 'var(--accent-container)',
                  color: 'var(--accent-container-text)',
                }}
              >
                <Lock className="w-3.5 h-3.5" />
                Lock
              </button>
              <button
                type="button"
                onClick={() => {
                  setLockError(null);
                  setLockSuccess(null);
                  setLockMode('reset');
                }}
                className="py-2 px-2 rounded-xl text-xs font-semibold border hover:bg-white/5 cursor-pointer"
                style={{ borderColor: 'var(--border-subtle)' }}
              >
                Reset
              </button>
              <button
                type="button"
                onClick={() => {
                  setLockError(null);
                  setLockSuccess(null);
                  setLockMode('unset');
                }}
                className="py-2 px-2 rounded-xl text-xs font-semibold text-rose-400 border border-rose-500/30 hover:bg-rose-500/10 cursor-pointer"
              >
                Unset
              </button>
            </div>
          ) : lockMode === 'reset' ? (
            <form onSubmit={handleResetPasscode} className="space-y-2 pt-1">
              <input
                type="password"
                value={currentPasscode}
                onChange={(e) => setCurrentPasscode(e.target.value)}
                placeholder="Current passcode"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <input
                type="password"
                value={newPasscode}
                onChange={(e) => setNewPasscode(e.target.value)}
                placeholder="New passcode (min 4 chars)"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <input
                type="password"
                value={confirmPasscode}
                onChange={(e) => setConfirmPasscode(e.target.value)}
                placeholder="Confirm new passcode"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={resetLockForm}
                  className="flex-1 py-2 rounded-xl text-xs border hover:bg-white/5 cursor-pointer"
                  style={{ borderColor: 'var(--border-subtle)' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={lockSaving}
                  className="flex-1 py-2 rounded-xl text-xs font-bold cursor-pointer"
                  style={{
                    background: 'var(--accent)',
                    color: 'var(--accent-on)',
                  }}
                >
                  {lockSaving ? 'Updating...' : 'Reset Lock'}
                </button>
              </div>
            </form>
          ) : (
            <form onSubmit={handleUnsetPasscode} className="space-y-2 pt-1">
              <input
                type="password"
                value={currentPasscode}
                onChange={(e) => setCurrentPasscode(e.target.value)}
                placeholder="Enter current passcode to unset"
                className="w-full rounded-xl px-3 py-2 text-xs border focus:outline-none"
                style={{
                  background: 'var(--bg-surface)',
                  borderColor: 'var(--border-subtle)',
                }}
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={resetLockForm}
                  className="flex-1 py-2 rounded-xl text-xs border hover:bg-white/5 cursor-pointer"
                  style={{ borderColor: 'var(--border-subtle)' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={lockSaving}
                  className="flex-1 py-2 rounded-xl text-xs font-bold bg-rose-500 text-white cursor-pointer"
                >
                  {lockSaving ? 'Removing...' : 'Confirm Unset'}
                </button>
              </div>
            </form>
          )}
        </div>

        {/* Firebase Info & Multi-Device Quick Login */}
        <div
          className="rounded-2xl p-4 border space-y-2.5"
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
              {copiedJson ? (
                <Check className="w-3.5 h-3.5 text-emerald-400" />
              ) : (
                <Copy className="w-3.5 h-3.5" />
              )}
              {copiedJson ? 'Copied' : 'Copy JSON'}
            </button>
          </div>
          <div className="text-sm font-semibold">{config.projectId}</div>
          <div className="text-[11px] opacity-65 break-all">
            Bucket: {config.storageBucket}
          </div>

          <button
            type="button"
            onClick={copyQuickLoginLink}
            className="w-full py-2 px-3 rounded-xl text-xs font-medium border flex items-center justify-center gap-1.5 hover:bg-white/5 cursor-pointer"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            {copiedQuickLink ? (
              <>
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                Quick-Login Link Copied!
              </>
            ) : (
              <>
                <Link className="w-3.5 h-3.5" />
                Copy Quick-Login Link for Other Devices
              </>
            )}
          </button>
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
            Sign Out &amp; Forget Browser Config
          </button>
        </div>
      </div>
    </div>
  );
};
