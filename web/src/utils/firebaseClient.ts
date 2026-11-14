import { initializeApp, getApps, deleteApp, FirebaseApp } from 'firebase/app';
import { getAuth, Auth } from 'firebase/auth';
import {
  getFirestore,
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager,
  Firestore,
} from 'firebase/firestore';
import { getStorage, FirebaseStorage } from 'firebase/storage';
import { FirebaseUserConfig } from '../types/note';

const CONFIG_STORAGE_KEY = 'pinotes_firebase_config_v1';
const ACCOUNT_CONFIGS_KEY = 'pinotes_account_configs_v1';
const APP_NAME = 'PINotesWebSync';

let cachedFirestore: Firestore | null = null;
let cachedAppId: string | null = null;

function normalizeConfig(config: Partial<FirebaseUserConfig>): FirebaseUserConfig | null {
  if (!config.apiKey?.trim() || !config.appId?.trim() || !config.projectId?.trim()) {
    return null;
  }
  const projectId = config.projectId.trim();
  return {
    apiKey: config.apiKey.trim(),
    appId: config.appId.trim(),
    projectId,
    storageBucket: config.storageBucket?.trim() || `${projectId}.firebasestorage.app`,
  };
}

/**
 * Checks URL hash (#fb=<base64>) for a quick-login config link shared from another device.
 * If found, saves it to localStorage and strips the hash from the address bar.
 */
export function importFirebaseConfigFromUrlHash(): FirebaseUserConfig | null {
  try {
    const hash = window.location.hash || '';
    const match = hash.match(/[#&]fb=([^&]+)/);
    if (!match) return null;
    const decoded = atob(decodeURIComponent(match[1]));
    const parsed = JSON.parse(decoded) as Partial<FirebaseUserConfig>;
    const normalized = normalizeConfig(parsed);
    if (normalized) {
      localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(normalized));
      window.history.replaceState(
        null,
        '',
        window.location.pathname + window.location.search
      );
      return normalized;
    }
  } catch {
    // ignore malformed hash
  }
  return null;
}

export function buildQuickLoginUrl(config: FirebaseUserConfig): string {
  const payload = btoa(JSON.stringify(config));
  const base = `${window.location.origin}${window.location.pathname}`;
  return `${base}#fb=${encodeURIComponent(payload)}`;
}

export function getEnvFirebaseConfig(): FirebaseUserConfig | null {
  const env = import.meta.env as Record<string, string | undefined>;
  return normalizeConfig({
    apiKey: env.VITE_FIREBASE_API_KEY,
    appId: env.VITE_FIREBASE_APP_ID,
    projectId: env.VITE_FIREBASE_PROJECT_ID,
    storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  });
}

export function loadSavedFirebaseConfig(): FirebaseUserConfig | null {
  const fromHash = importFirebaseConfigFromUrlHash();
  if (fromHash) return fromHash;

  try {
    const raw = localStorage.getItem(CONFIG_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<FirebaseUserConfig>;
      const normalized = normalizeConfig(parsed);
      if (normalized) return normalized;
    }
  } catch {
    // ignore malformed local storage
  }

  return getEnvFirebaseConfig();
}

export function saveFirebaseConfig(
  config: FirebaseUserConfig,
  email?: string
): FirebaseUserConfig {
  const normalized = normalizeConfig(config)!;
  localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(normalized));

  if (email && email.trim()) {
    try {
      const rawMap = localStorage.getItem(ACCOUNT_CONFIGS_KEY);
      const map = rawMap ? (JSON.parse(rawMap) as Record<string, FirebaseUserConfig>) : {};
      map[email.trim().toLowerCase()] = normalized;
      localStorage.setItem(ACCOUNT_CONFIGS_KEY, JSON.stringify(map));
    } catch {
      // ignore storage quota errors
    }
  }

  return normalized;
}

export function getFirebaseConfigForEmail(email: string): FirebaseUserConfig | null {
  if (!email.trim()) return loadSavedFirebaseConfig();
  try {
    const rawMap = localStorage.getItem(ACCOUNT_CONFIGS_KEY);
    if (rawMap) {
      const map = JSON.parse(rawMap) as Record<string, FirebaseUserConfig>;
      const match = map[email.trim().toLowerCase()];
      const normalized = match ? normalizeConfig(match) : null;
      if (normalized) return normalized;
    }
  } catch {
    // ignore
  }
  return loadSavedFirebaseConfig();
}

export function clearSavedFirebaseConfig(): void {
  localStorage.removeItem(CONFIG_STORAGE_KEY);
}

/**
 * Parses a pasted Firebase Console JS snippet like:
 * const firebaseConfig = { apiKey: "...", projectId: "...", ... };
 * or raw JSON.
 */
export function parseFirebaseConfigSnippet(snippet: string): Partial<FirebaseUserConfig> | null {
  if (!snippet.trim()) return null;
  const extract = (key: string): string | undefined => {
    const regex = new RegExp(`["']?${key}["']?\\s*:\\s*["']([^"']+)["']`);
    const match = snippet.match(regex);
    return match ? match[1].trim() : undefined;
  };

  const apiKey = extract('apiKey');
  const projectId = extract('projectId');
  const appId = extract('appId');
  const storageBucket = extract('storageBucket');

  if (!apiKey && !projectId && !appId) return null;
  return {
    apiKey: apiKey || '',
    projectId: projectId || '',
    appId: appId || '',
    storageBucket: storageBucket || (projectId ? `${projectId}.firebasestorage.app` : ''),
  };
}

export async function getOrInitFirebaseInstances(config: FirebaseUserConfig): Promise<{
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
  storage: FirebaseStorage;
}> {
  const existing = getApps().find((a) => a.name === APP_NAME);
  const configKey = `${config.projectId}|${config.apiKey}|${config.appId}|${config.storageBucket}`;

  if (existing && cachedAppId === configKey && cachedFirestore) {
    return {
      app: existing,
      auth: getAuth(existing),
      db: cachedFirestore,
      storage: getStorage(existing),
    };
  }

  if (existing) {
    await deleteApp(existing);
    cachedFirestore = null;
  }

  const app = initializeApp(
    {
      apiKey: config.apiKey,
      appId: config.appId,
      projectId: config.projectId,
      storageBucket: config.storageBucket || `${config.projectId}.firebasestorage.app`,
    },
    APP_NAME
  );

  let db: Firestore;
  try {
    db = initializeFirestore(app, {
      localCache: persistentLocalCache({
        tabManager: persistentMultipleTabManager(),
      }),
    });
  } catch {
    db = getFirestore(app);
  }

  cachedFirestore = db;
  cachedAppId = configKey;

  return {
    app,
    auth: getAuth(app),
    db,
    storage: getStorage(app),
  };
}

export async function teardownFirebaseInstance(): Promise<void> {
  const existing = getApps().find((a) => a.name === APP_NAME);
  if (existing) {
    await deleteApp(existing);
  }
  cachedFirestore = null;
  cachedAppId = null;
}
