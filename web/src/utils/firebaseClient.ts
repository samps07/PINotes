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
const APP_NAME = 'PINotesWebSync';

let cachedFirestore: Firestore | null = null;
let cachedAppId: string | null = null;

export function loadSavedFirebaseConfig(): FirebaseUserConfig | null {
  try {
    const raw = localStorage.getItem(CONFIG_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<FirebaseUserConfig>;
    if (parsed.apiKey && parsed.appId && parsed.projectId) {
      return {
        apiKey: parsed.apiKey.trim(),
        appId: parsed.appId.trim(),
        projectId: parsed.projectId.trim(),
        storageBucket:
          parsed.storageBucket?.trim() || `${parsed.projectId.trim()}.firebasestorage.app`,
      };
    }
  } catch {
    // ignore malformed local storage
  }
  return null;
}

export function saveFirebaseConfig(config: FirebaseUserConfig): FirebaseUserConfig {
  const normalized: FirebaseUserConfig = {
    apiKey: config.apiKey.trim(),
    appId: config.appId.trim(),
    projectId: config.projectId.trim(),
    storageBucket:
      config.storageBucket.trim() || `${config.projectId.trim()}.firebasestorage.app`,
  };
  localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(normalized));
  return normalized;
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
