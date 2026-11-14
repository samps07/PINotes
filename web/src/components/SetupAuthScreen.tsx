import React, { useState } from 'react';
import {
  Cloud,
  KeyRound,
  Mail,
  Lock,
  Sparkles,
  ChevronDown,
  ChevronUp,
  CheckCircle2,
  AlertCircle,
  Loader2,
  ClipboardPaste,
} from 'lucide-react';
import { FirebaseUserConfig } from '../types/note';
import { parseFirebaseConfigSnippet } from '../utils/firebaseClient';

interface SetupAuthScreenProps {
  initialConfig: FirebaseUserConfig | null;
  onConnect: (
    config: FirebaseUserConfig,
    email: string,
    password: string,
    mode: 'signin' | 'signup'
  ) => Promise<void>;
  isDark: boolean;
  onToggleTheme: () => void;
}

export const SetupAuthScreen: React.FC<SetupAuthScreenProps> = ({
  initialConfig,
  onConnect,
  isDark,
  onToggleTheme,
}) => {
  const [apiKey, setApiKey] = useState(initialConfig?.apiKey || '');
  const [projectId, setProjectId] = useState(initialConfig?.projectId || '');
  const [appId, setAppId] = useState(initialConfig?.appId || '');
  const [storageBucket, setStorageBucket] = useState(initialConfig?.storageBucket || '');

  const [showConfigSection, setShowConfigSection] = useState(!initialConfig);
  const [showSnippetBox, setShowSnippetBox] = useState(false);
  const [snippetText, setSnippetText] = useState('');

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [infoMsg, setInfoMsg] = useState<string | null>(null);

  const handleParseSnippet = () => {
    const parsed = parseFirebaseConfigSnippet(snippetText);
    if (!parsed) {
      setErrorMsg('Could not detect apiKey, projectId, or appId in the pasted snippet.');
      return;
    }
    if (parsed.apiKey) setApiKey(parsed.apiKey);
    if (parsed.projectId) setProjectId(parsed.projectId);
    if (parsed.appId) setAppId(parsed.appId);
    if (parsed.storageBucket) setStorageBucket(parsed.storageBucket);
    setErrorMsg(null);
    setInfoMsg('Firebase credentials extracted from snippet!');
    setShowSnippetBox(false);
    setSnippetText('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setInfoMsg(null);

    if (!apiKey.trim() || !projectId.trim() || !appId.trim()) {
      setShowConfigSection(true);
      setErrorMsg('Please enter your Firebase API Key, Project ID, and App ID.');
      return;
    }
    if (!email.trim() || !password) {
      setErrorMsg('Please enter your email and password.');
      return;
    }

    setIsLoading(true);
    try {
      await onConnect(
        {
          apiKey: apiKey.trim(),
          projectId: projectId.trim(),
          appId: appId.trim(),
          storageBucket:
            storageBucket.trim() || `${projectId.trim()}.firebasestorage.app`,
        },
        email.trim(),
        password,
        authMode
      );
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to connect to Firebase.';
      setErrorMsg(msg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4 sm:p-6 relative overflow-hidden">
      {/* Subtle decorative glow */}
      <div
        className="pointer-events-none absolute -top-40 left-1/2 -translate-x-1/2 w-[540px] h-[540px] rounded-full blur-[120px] opacity-20"
        style={{ background: 'var(--accent)' }}
      />

      <div className="w-full max-w-lg relative z-10">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <div
              className="w-11 h-11 rounded-2xl flex items-center justify-center shadow-md"
              style={{
                background: 'var(--accent-container)',
                color: 'var(--accent-container-text)',
              }}
            >
              <svg viewBox="0 0 24 24" className="w-6 h-6 fill-current">
                <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5v6l1 1 1-1v-6h5v-2l-2-2z" />
              </svg>
            </div>
            <div>
              <h1 className="text-2xl font-bold tracking-tight">PINotes Web</h1>
              <p className="text-xs opacity-70">Cross-device cloud notes synced with Android</p>
            </div>
          </div>

          <button
            type="button"
            onClick={onToggleTheme}
            className="px-3 py-1.5 rounded-full text-xs font-medium border transition-colors cursor-pointer"
            style={{
              background: 'var(--bg-surface)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            {isDark ? 'Light Mode' : 'Dark Mode'}
          </button>
        </div>

        {/* Main Card */}
        <form
          onSubmit={handleSubmit}
          className="rounded-3xl p-6 sm:p-7 shadow-xl border space-y-5"
          style={{
            background: 'var(--bg-surface)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          {/* Firebase Config Section */}
          <div
            className="rounded-2xl border overflow-hidden"
            style={{
              background: 'var(--bg-canvas)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            <button
              type="button"
              onClick={() => setShowConfigSection((v) => !v)}
              className="w-full flex items-center justify-between px-4 py-3 text-left cursor-pointer"
            >
              <div className="flex items-center gap-2.5">
                <Cloud className="w-4 h-4" style={{ color: 'var(--accent)' }} />
                <div>
                  <div className="text-sm font-semibold">
                    Firebase Project Configuration
                  </div>
                  <div className="text-xs opacity-65">
                    {projectId
                      ? `Connected target: ${projectId}`
                      : 'Enter the same Firebase config used in your Android app'}
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                {apiKey && projectId && appId && (
                  <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                )}
                {showConfigSection ? (
                  <ChevronUp className="w-4 h-4 opacity-60" />
                ) : (
                  <ChevronDown className="w-4 h-4 opacity-60" />
                )}
              </div>
            </button>

            {showConfigSection && (
              <div className="px-4 pb-4 pt-1 space-y-3 border-t" style={{ borderColor: 'var(--border-subtle)' }}>
                <div className="flex items-center justify-between pt-2">
                  <span className="text-xs opacity-70">
                    Find these in Android PINotes → Cloud Sync or Firebase Console
                  </span>
                  <button
                    type="button"
                    onClick={() => setShowSnippetBox((v) => !v)}
                    className="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-lg cursor-pointer"
                    style={{
                      background: 'var(--accent-container)',
                      color: 'var(--accent-container-text)',
                    }}
                  >
                    <ClipboardPaste className="w-3.5 h-3.5" />
                    Paste Snippet
                  </button>
                </div>

                {showSnippetBox && (
                  <div className="space-y-2 pt-1">
                    <textarea
                      value={snippetText}
                      onChange={(e) => setSnippetText(e.target.value)}
                      rows={4}
                      placeholder={'Paste Firebase config snippet:\nconst firebaseConfig = {\n  apiKey: "...",\n  projectId: "...",\n  appId: "..."\n};'}
                      className="w-full rounded-xl px-3 py-2 text-xs font-mono border focus:outline-none"
                      style={{
                        background: 'var(--bg-surface)',
                        borderColor: 'var(--border-subtle)',
                      }}
                    />
                    <button
                      type="button"
                      onClick={handleParseSnippet}
                      className="px-3 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
                      style={{
                        background: 'var(--accent)',
                        color: 'var(--accent-on)',
                      }}
                    >
                      Auto-fill Fields
                    </button>
                  </div>
                )}

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  <div>
                    <label className="block text-[11px] font-medium opacity-70 mb-1">
                      Project ID *
                    </label>
                    <input
                      type="text"
                      value={projectId}
                      onChange={(e) => setProjectId(e.target.value)}
                      placeholder="my-pinotes-project"
                      className="w-full rounded-xl px-3 py-2 text-sm border focus:outline-none"
                      style={{
                        background: 'var(--bg-surface)',
                        borderColor: 'var(--border-subtle)',
                      }}
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] font-medium opacity-70 mb-1">
                      API Key *
                    </label>
                    <input
                      type="text"
                      value={apiKey}
                      onChange={(e) => setApiKey(e.target.value)}
                      placeholder="AIzaSy..."
                      className="w-full rounded-xl px-3 py-2 text-sm border focus:outline-none"
                      style={{
                        background: 'var(--bg-surface)',
                        borderColor: 'var(--border-subtle)',
                      }}
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] font-medium opacity-70 mb-1">
                      App ID *
                    </label>
                    <input
                      type="text"
                      value={appId}
                      onChange={(e) => setAppId(e.target.value)}
                      placeholder="1:123456:web:abcdef"
                      className="w-full rounded-xl px-3 py-2 text-sm border focus:outline-none"
                      style={{
                        background: 'var(--bg-surface)',
                        borderColor: 'var(--border-subtle)',
                      }}
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] font-medium opacity-70 mb-1">
                      Storage Bucket
                    </label>
                    <input
                      type="text"
                      value={storageBucket}
                      onChange={(e) => setStorageBucket(e.target.value)}
                      placeholder="project.firebasestorage.app"
                      className="w-full rounded-xl px-3 py-2 text-sm border focus:outline-none"
                      style={{
                        background: 'var(--bg-surface)',
                        borderColor: 'var(--border-subtle)',
                      }}
                    />
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Auth Mode Tabs */}
          <div
            className="grid grid-cols-2 p-1 rounded-2xl border"
            style={{
              background: 'var(--bg-canvas)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            <button
              type="button"
              onClick={() => setAuthMode('signin')}
              className="py-2 rounded-xl text-sm font-semibold transition-all cursor-pointer"
              style={
                authMode === 'signin'
                  ? {
                      background: 'var(--accent-container)',
                      color: 'var(--accent-container-text)',
                    }
                  : { opacity: 0.7 }
              }
            >
              Sign In
            </button>
            <button
              type="button"
              onClick={() => setAuthMode('signup')}
              className="py-2 rounded-xl text-sm font-semibold transition-all cursor-pointer"
              style={
                authMode === 'signup'
                  ? {
                      background: 'var(--accent-container)',
                      color: 'var(--accent-container-text)',
                    }
                  : { opacity: 0.7 }
              }
            >
              Create Account
            </button>
          </div>

          {/* Email & Password */}
          <div className="space-y-3">
            <div>
              <label className="block text-xs font-medium opacity-75 mb-1.5">
                Email Address
              </label>
              <div className="relative">
                <Mail className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 opacity-50" />
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  className="w-full rounded-xl pl-10 pr-3.5 py-2.5 text-sm border focus:outline-none"
                  style={{
                    background: 'var(--bg-canvas)',
                    borderColor: 'var(--border-subtle)',
                  }}
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium opacity-75 mb-1.5">
                Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 opacity-50" />
                <input
                  type="password"
                  required
                  minLength={6}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full rounded-xl pl-10 pr-3.5 py-2.5 text-sm border focus:outline-none"
                  style={{
                    background: 'var(--bg-canvas)',
                    borderColor: 'var(--border-subtle)',
                  }}
                />
              </div>
            </div>
          </div>

          {infoMsg && (
            <div className="flex items-start gap-2 rounded-xl px-3.5 py-2.5 text-xs bg-emerald-500/15 text-emerald-300 border border-emerald-500/30">
              <Sparkles className="w-4 h-4 shrink-0 mt-0.5" />
              <span>{infoMsg}</span>
            </div>
          )}

          {errorMsg && (
            <div className="flex items-start gap-2 rounded-xl px-3.5 py-2.5 text-xs bg-rose-500/15 text-rose-300 border border-rose-500/30">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <span>{errorMsg}</span>
            </div>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-3 rounded-2xl text-sm font-bold flex items-center justify-center gap-2 transition-transform active:scale-[0.99] cursor-pointer disabled:opacity-60"
            style={{
              background: 'var(--accent)',
              color: 'var(--accent-on)',
            }}
          >
            {isLoading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                Connecting to Firebase...
              </>
            ) : (
              <>
                <KeyRound className="w-4 h-4" />
                {authMode === 'signin' ? 'Connect & Sync Notes' : 'Register & Start Syncing'}
              </>
            )}
          </button>

          <p className="text-[11px] text-center opacity-60 leading-relaxed">
            Your Firebase credentials stay strictly in your browser&apos;s local storage and
            communicate directly with your private Firebase project.
          </p>
        </form>
      </div>
    </div>
  );
};
