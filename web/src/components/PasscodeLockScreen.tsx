import React, { useState } from 'react';
import { Lock, LogOut, Eye, EyeOff, AlertCircle, ShieldCheck } from 'lucide-react';

interface PasscodeLockScreenProps {
  userEmail: string | null;
  onVerify: (passcode: string) => Promise<boolean>;
  onUnlockSuccess: () => void;
  onSignOut: () => Promise<void>;
}

export const PasscodeLockScreen: React.FC<PasscodeLockScreenProps> = ({
  userEmail,
  onVerify,
  onUnlockSuccess,
  onSignOut,
}) => {
  const [passcode, setPasscode] = useState('');
  const [showCode, setShowCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passcode.trim()) {
      setError('Enter your web app passcode.');
      return;
    }
    setChecking(true);
    setError(null);
    try {
      const ok = await onVerify(passcode);
      if (ok) {
        onUnlockSuccess();
      } else {
        setError('Incorrect passcode. Please try again.');
        setPasscode('');
      }
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden">
      <div
        className="pointer-events-none absolute -top-36 left-1/2 -translate-x-1/2 w-[460px] h-[460px] rounded-full blur-[120px] opacity-20"
        style={{ background: 'var(--accent)' }}
      />

      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm rounded-3xl border p-6 sm:p-7 shadow-2xl space-y-5 relative z-10"
        style={{
          background: 'var(--bg-surface)',
          borderColor: 'var(--border-subtle)',
        }}
      >
        <div className="flex flex-col items-center text-center space-y-2">
          <div
            className="w-14 h-14 rounded-2xl flex items-center justify-center shadow-md"
            style={{
              background: 'var(--accent-container)',
              color: 'var(--accent-container-text)',
            }}
          >
            <ShieldCheck className="w-7 h-7" />
          </div>
          <h1 className="font-display text-xl font-bold">PINotes Web Locked</h1>
          <p className="text-xs opacity-70">
            Enter your web passcode to unlock your notes
            {userEmail ? ` (${userEmail})` : ''}.
          </p>
        </div>

        <div className="space-y-2">
          <div className="relative">
            <Lock className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 opacity-50" />
            <input
              type={showCode ? 'text' : 'password'}
              value={passcode}
              onChange={(e) => setPasscode(e.target.value)}
              placeholder="Enter passcode"
              autoFocus
              className="w-full rounded-2xl pl-10 pr-10 py-3 text-sm tracking-widest border focus:outline-none focus:ring-2 focus:ring-[#D0BCFF]/40"
              style={{
                background: 'var(--bg-canvas)',
                borderColor: 'var(--border-subtle)',
              }}
            />
            <button
              type="button"
              onClick={() => setShowCode((v) => !v)}
              title={showCode ? 'Hide passcode' : 'Show passcode'}
              className="absolute right-3 top-1/2 -translate-y-1/2 p-1 opacity-60 hover:opacity-100 cursor-pointer"
            >
              {showCode ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
            </button>
          </div>

          {error && (
            <div className="flex items-center gap-2 rounded-xl px-3 py-2 text-xs bg-rose-500/15 text-rose-300 border border-rose-500/30">
              <AlertCircle className="w-3.5 h-3.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}
        </div>

        <button
          type="submit"
          disabled={checking}
          className="w-full py-3 rounded-2xl text-sm font-bold cursor-pointer disabled:opacity-60 transition-transform active:scale-[0.99]"
          style={{
            background: 'var(--accent)',
            color: 'var(--accent-on)',
          }}
        >
          {checking ? 'Verifying...' : 'Unlock Notes'}
        </button>

        <button
          type="button"
          onClick={onSignOut}
          className="w-full py-2 rounded-xl text-xs font-medium opacity-70 hover:opacity-100 flex items-center justify-center gap-1.5 cursor-pointer"
        >
          <LogOut className="w-3.5 h-3.5" />
          Sign out / Switch account
        </button>
      </form>
    </div>
  );
};
