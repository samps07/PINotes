import React, { useState } from 'react';
import {
  Cloud,
  KeyRound,
  Mail,
  Lock,
  Sparkles,
  CheckCircle2,
  AlertCircle,
  Loader2,
  ClipboardPaste,
  Link2,
} from 'lucide-react';
import { FirebaseUserConfig } from '../types/note';
import {
  getEnvFirebaseConfig,
  getFirebaseConfigForEmail,
  parseFirebaseConfigSnippet,
} from '../utils/firebaseClient';
import {
  lookupEncryptedConfigFromDirectory,
  parseAnyAccountLinkOrCode,
} from '../services/accountConfigService';

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
  const envDefaultConfig = getEnvFirebaseConfig();
  const baseInitial = initialConfig || envDefaultConfig;

  const [apiKey, setApiKey] = useState(baseInitial?.apiKey || '');
  const [projectId, setProjectId] = useState(baseInitial?.projectId || '');
  const [appId, setAppId] = useState(baseInitial?.appId || '');
  const [storageBucket, setStorageBucket] = useState(baseInitial?.storageBucket || '');

  const [showSnippetBox, setShowSnippetBox] = useState(false);
  const [snippetText, setSnippetText] = useState('');

  // For brand-new browsers when no baked-in VITE_FIREBASE_* or cloud directory entry exists
  const [needsDeviceLink, setNeedsDeviceLink] = useState(false);
  const [showManualSignInConfig, setShowManualSignInConfig] = useState(false);
  const [accountCodeInput, setAccountCodeInput] = useState('');

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [infoMsg, setInfoMsg] = useState<string | null>(null);

  const applyResolvedConfig = (cfg: FirebaseUserConfig) => {
    setApiKey(cfg.apiKey);
    setProjectId(cfg.projectId);
    setAppId(cfg.appId);
    setStorageBucket(cfg.storageBucket);
  };

  const handleEmailChange = (val: string) => {
    setEmail(val);
    const matched = getFirebaseConfigForEmail(val) || envDefaultConfig;
    if (matched) {
      applyResolvedConfig(matched);
      setNeedsDeviceLink(false);
    }
  };

  const handleApplyAccountCode = () => {
    setErrorMsg(null);
    const parsedFromCode = parseAnyAccountLinkOrCode(accountCodeInput);
    if (parsedFromCode) {
      applyResolvedConfig(parsedFromCode);
      setNeedsDeviceLink(false);
      setShowManualSignInConfig(false);
      setAccountCodeInput('');
      setInfoMsg(`Linked workspace "${parsedFromCode.projectId}". Click Sign In to continue.`);
      return;
    }

    const parsedSnippet = parseFirebaseConfigSnippet(accountCodeInput);
    if (parsedSnippet?.apiKey && parsedSnippet.projectId && parsedSnippet.appId) {
      applyResolvedConfig({
        apiKey: parsedSnippet.apiKey,
        projectId: parsedSnippet.projectId,
        appId: parsedSnippet.appId,
        storageBucket:
          parsedSnippet.storageBucket || `${parsedSnippet.projectId}.firebasestorage.app`,
      });
      setNeedsDeviceLink(false);
      setShowManualSignInConfig(false);
      setAccountCodeInput('');
      setInfoMsg(`Linked workspace "${parsedSnippet.projectId}". Click Sign In to continue.`);
      return;
    }

    setErrorMsg(
      'Invalid Portable Account Code or Quick-Login Link. Copy it from PINotes Web Settings on an already-signed-in device.'
    );
  };

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

    if (!email.trim() || !password) {
      setErrorMsg('Please enter your email and password.');
      return;
    }

    setIsLoading(true);
    try {
      let effectiveConfig: FirebaseUserConfig | null = null;

      // 1. If user entered a portable code inline without pressing Apply first
      if (accountCodeInput.trim()) {
        effectiveConfig = parseAnyAccountLinkOrCode(accountCodeInput);
      }

      // 2. Check form / per-email localStorage / baked-in VITE_FIREBASE_*
      if (!effectiveConfig) {
        const savedForEmail =
          getFirebaseConfigForEmail(email) || initialConfig || envDefaultConfig;
        const effectiveApiKey = apiKey.trim() || savedForEmail?.apiKey || '';
        const effectiveProjectId = projectId.trim() || savedForEmail?.projectId || '';
        const effectiveAppId = appId.trim() || savedForEmail?.appId || '';
        const effectiveBucket =
          storageBucket.trim() ||
          savedForEmail?.storageBucket ||
          (effectiveProjectId ? `${effectiveProjectId}.firebasestorage.app` : '');

        if (effectiveApiKey && effectiveProjectId && effectiveAppId) {
          effectiveConfig = {
            apiKey: effectiveApiKey,
            projectId: effectiveProjectId,
            appId: effectiveAppId,
            storageBucket: effectiveBucket,
          };
        }
      }

      // 3. On Sign In, also attempt encrypted cloud directory lookup using (email + password)
      if (!effectiveConfig && authMode === 'signin') {
        const recovered = await lookupEncryptedConfigFromDirectory(email, password);
        if (recovered) {
          effectiveConfig = recovered;
          applyResolvedConfig(recovered);
        }
      }

      if (!effectiveConfig) {
        if (authMode === 'signin') {
          setNeedsDeviceLink(true);
          setErrorMsg(
            'This is a fresh browser and this site deployment does not have a default Firebase project baked in yet. Paste your Quick-Login Link / Portable Account Code from your other device once (or enter your Firebase config manually below).'
          );
        } else {
          setErrorMsg(
            'Please enter your Firebase API Key, Project ID, and App ID to create a new account.'
          );
        }
        return;
      }

      await onConnect(effectiveConfig, email.trim(), password, authMode);
    } catch (err: unknown) {
      if (authMode === 'signin' && !showManualSignInConfig) {
        const recovered = await lookupEncryptedConfigFromDirectory(email, password);
        if (recovered && recovered.projectId !== projectId.trim()) {
          try {
            applyResolvedConfig(recovered);
            await onConnect(recovered, email.trim(), password, authMode);
            return;
          } catch (retryErr: unknown) {
            const retryMsg =
              retryErr instanceof Error ? retryErr.message : 'Failed to connect to Firebase.';
            setErrorMsg(retryMsg);
            return;
          }
        }
      }
      const msg = err instanceof Error ? err.message : 'Failed to connect to Firebase.';
      setErrorMsg(msg);
    } finally {
      setIsLoading(false);
    }
  };

  const shouldShowFirebaseForm = authMode === 'signup' || showManualSignInConfig;

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4 sm:p-6 relative overflow-hidden">
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
              onClick={() => {
                setAuthMode('signin');
                setErrorMsg(null);
              }}
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
              onClick={() => {
                setAuthMode('signup');
                setNeedsDeviceLink(false);
                setErrorMsg(null);
              }}
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

          {/* Ready Workspace Badge on Sign In */}
          {authMode === 'signin' && !showManualSignInConfig && projectId && (
            <div
              className="flex items-center justify-between px-3.5 py-2.5 rounded-2xl border text-xs"
              style={{
                background: 'var(--bg-canvas)',
                borderColor: 'var(--border-subtle)',
              }}
            >
              <span className="flex items-center gap-2 opacity-80">
                <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                <span>
                  Workspace ready: <strong>{projectId}</strong>
                </span>
              </span>
              <button
                type="button"
                onClick={() => setShowManualSignInConfig(true)}
                className="text-[11px] font-medium underline opacity-70 hover:opacity-100 cursor-pointer"
              >
                Switch project
              </button>
            </div>
          )}

          {/* First-Time New-Device Link (only shown if Sign In couldn't find any config yet) */}
          {authMode === 'signin' && needsDeviceLink && !showManualSignInConfig && (
            <div
              className="rounded-2xl border p-4 space-y-3"
              style={{
                background: 'var(--bg-canvas)',
                borderColor: 'var(--border-subtle)',
              }}
            >
              <div className="flex items-center gap-2">
                <Link2 className="w-4 h-4 shrink-0" style={{ color: 'var(--accent)' }} />
                <div>
                  <div className="text-xs font-bold">Link Account on This New Device</div>
                  <div className="text-[11px] opacity-65">
                    Paste your Portable Account Code or Quick-Login Link from Settings on your
                    other device (one-time per browser).
                  </div>
                </div>
              </div>

              <div className="flex gap-2">
                <input
                  type="text"
                  value={accountCodeInput}
                  onChange={(e) => setAccountCodeInput(e.target.value)}
                  placeholder="Paste PINOTES1:... code or Quick-Login URL"
                  className="flex-1 rounded-xl px-3 py-2 text-xs font-mono border focus:outline-none"
                  style={{
                    background: 'var(--bg-surface)',
                    borderColor: 'var(--border-subtle)',
                  }}
                />
                <button
                  type="button"
                  onClick={handleApplyAccountCode}
                  className="px-3 py-2 rounded-xl text-xs font-bold shrink-0 cursor-pointer"
                  style={{
                    background: 'var(--accent-container)',
                    color: 'var(--accent-container-text)',
                  }}
                >
                  Apply
                </button>
              </div>

              <button
                type="button"
                onClick={() => setShowManualSignInConfig(true)}
                className="text-[11px] underline opacity-70 hover:opacity-100 cursor-pointer"
              >
                Or enter Firebase Project ID &amp; API Key manually
              </button>
            </div>
          )}

          {/* Firebase Config Section (Shown on Create Account, or if user explicitly opens manual config) */}
          {shouldShowFirebaseForm && (
            <div
              className="rounded-2xl border overflow-hidden p-4 space-y-3"
              style={{
                background: 'var(--bg-canvas)',
                borderColor: 'var(--border-subtle)',
              }}
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Cloud className="w-4 h-4 shrink-0" style={{ color: 'var(--accent)' }} />
                  <div>
                    <div className="text-sm font-semibold">Firebase Project Details</div>
                    <div className="text-[11px] opacity-65">
                      {authMode === 'signup'
                        ? 'Attached to your account & remembered across sessions'
                        : 'One-time browser link'}
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setShowSnippetBox((v) => !v)}
                  className="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-lg cursor-pointer shrink-0"
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
                  autoComplete="email"
                  value={email}
                  onChange={(e) => handleEmailChange(e.target.value)}
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
                  autoComplete={authMode === 'signin' ? 'current-password' : 'new-password'}
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
                {authMode === 'signin' ? 'Sign In & Sync Notes' : 'Create Account & Sync'}
              </>
            )}
          </button>

          <p className="text-[11px] text-center opacity-60 leading-relaxed">
            {authMode === 'signin'
              ? 'Existing users sign in directly with email and password.'
              : 'Your Firebase project details are linked to your account for seamless syncing.'}
          </p>
        </form>
      </div>
    </div>
  );
};
