import {
  doc,
  getDoc,
  setDoc,
  Firestore,
  initializeFirestore,
  getFirestore,
} from 'firebase/firestore';
import { initializeApp, getApps } from 'firebase/app';
import { FirebaseUserConfig } from '../types/note';

const REGISTRY_APP_NAME = 'PINotesAccountDirectory';

function normalizeRegistryConfig(
  cfg: Partial<FirebaseUserConfig>
): FirebaseUserConfig | null {
  if (!cfg.apiKey?.trim() || !cfg.appId?.trim() || !cfg.projectId?.trim()) return null;
  const projectId = cfg.projectId.trim();
  return {
    apiKey: cfg.apiKey.trim(),
    appId: cfg.appId.trim(),
    projectId,
    storageBucket: cfg.storageBucket?.trim() || `${projectId}.firebasestorage.app`,
  };
}

export function getRegistryFirebaseConfig(): FirebaseUserConfig | null {
  const env = import.meta.env as Record<string, string | undefined>;
  const explicit = normalizeRegistryConfig({
    apiKey: env.VITE_REGISTRY_FIREBASE_API_KEY,
    appId: env.VITE_REGISTRY_FIREBASE_APP_ID,
    projectId: env.VITE_REGISTRY_FIREBASE_PROJECT_ID,
    storageBucket: env.VITE_REGISTRY_FIREBASE_STORAGE_BUCKET,
  });
  if (explicit) return explicit;

  // Fallback to the build-level default Firebase project if configured
  return normalizeRegistryConfig({
    apiKey: env.VITE_FIREBASE_API_KEY,
    appId: env.VITE_FIREBASE_APP_ID,
    projectId: env.VITE_FIREBASE_PROJECT_ID,
    storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  });
}

function encodeBase64Utf8(str: string): string {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function decodeBase64Utf8(b64: string): string {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/**
 * Generates a compact one-line Portable Account Code containing the user's Firebase config.
 * Can also be embedded inside URLs (#account=...) or pasted directly when switching devices.
 */
export function buildPortableAccountCode(config: FirebaseUserConfig): string {
  const payload = JSON.stringify({
    k: config.apiKey,
    a: config.appId,
    p: config.projectId,
    b: config.storageBucket,
  });
  return `PINOTES1:${encodeBase64Utf8(payload)}`;
}

/**
 * Parses a Portable Account Code (`PINOTES1:...`), a Quick-Login URL (`#fb=...` or `#account=...`),
 * or a Firebase config JSON / JS snippet.
 */
export function parseAnyAccountLinkOrCode(raw: string): FirebaseUserConfig | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;

  // 1. Portable Account Code `PINOTES1:...`
  const codeMatch = trimmed.match(/PINOTES1:([A-Za-z0-9+/=_-]+)/);
  if (codeMatch) {
    try {
      const json = JSON.parse(decodeBase64Utf8(codeMatch[1])) as Record<string, string>;
      return normalizeRegistryConfig({
        apiKey: json.k || json.apiKey,
        appId: json.a || json.appId,
        projectId: json.p || json.projectId,
        storageBucket: json.b || json.storageBucket,
      });
    } catch {
      // fall through
    }
  }

  // 2. Quick-Login URL with #fb= or #account=
  const hashMatch = trimmed.match(/[#&](?:fb|account)=([^&\s]+)/);
  if (hashMatch) {
    try {
      const decoded = decodeURIComponent(hashMatch[1]);
      if (decoded.startsWith('PINOTES1:')) {
        return parseAnyAccountLinkOrCode(decoded);
      }
      const parsed = JSON.parse(atob(decoded)) as Partial<FirebaseUserConfig>;
      return normalizeRegistryConfig(parsed);
    } catch {
      // fall through
    }
  }

  return null;
}

async function sha256Hex(text: string): Promise<string> {
  if (typeof window !== 'undefined' && window.crypto?.subtle) {
    const digest = await window.crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(text)
    );
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return `fb_${(h >>> 0).toString(16)}`;
}

async function deriveAesKey(email: string, password: string, saltBytes: BufferSource): Promise<CryptoKey> {
  const keyMaterial = await window.crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(`${email.trim().toLowerCase()}::${password}`),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  return window.crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: saltBytes,
      iterations: 100000,
      hash: 'SHA-256',
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

function bytesToBase64(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function base64ToBytes(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function getRegistryFirestore(): Firestore | null {
  const cfg = getRegistryFirebaseConfig();
  if (!cfg) return null;
  try {
    const existing = getApps().find((a) => a.name === REGISTRY_APP_NAME);
    const app =
      existing ||
      initializeApp(
        {
          apiKey: cfg.apiKey,
          appId: cfg.appId,
          projectId: cfg.projectId,
          storageBucket: cfg.storageBucket,
        },
        REGISTRY_APP_NAME
      );
    try {
      return initializeFirestore(app, {});
    } catch {
      return getFirestore(app);
    }
  } catch {
    return null;
  }
}

/**
 * Stores a backup copy of the user's Firebase config inside their own authenticated Firestore
 * under users/{uid}/config/firebaseProject so it stays attached to their account.
 */
export async function saveAccountFirebaseConfigToUserDoc(
  db: Firestore,
  uid: string,
  email: string,
  config: FirebaseUserConfig
): Promise<void> {
  try {
    const ref = doc(db, 'users', uid, 'config', 'firebaseProject');
    await setDoc(
      ref,
      {
        email: email.trim().toLowerCase(),
        apiKey: config.apiKey,
        appId: config.appId,
        projectId: config.projectId,
        storageBucket: config.storageBucket,
        updatedAt: Date.now(),
      },
      { merge: true }
    );
  } catch {
    // Non-fatal if rules are still propagating
  }
}

/**
 * If a central directory/default Firebase project is configured (VITE_REGISTRY_FIREBASE_* or VITE_FIREBASE_*),
 * encrypts the user's Firebase config client-side with PBKDF2(email + password) -> AES-256-GCM
 * so logging in from any completely new device recovers their Firebase config automatically.
 * Gracefully no-ops if directory writes aren't allowed by the project's Firestore rules.
 */
export async function saveEncryptedConfigToDirectory(
  email: string,
  password: string,
  config: FirebaseUserConfig
): Promise<void> {
  if (!window.crypto?.subtle || !email.trim() || !password) return;
  const dirDb = getRegistryFirestore();
  if (!dirDb) return;

  try {
    const cleanEmail = email.trim().toLowerCase();
    const emailHash = await sha256Hex(`pinotes_dir_v1::${cleanEmail}`);
    const salt = window.crypto.getRandomValues(new Uint8Array(new ArrayBuffer(16)));
    const iv = window.crypto.getRandomValues(new Uint8Array(new ArrayBuffer(12)));
    const aesKey = await deriveAesKey(cleanEmail, password, salt);
    const plainBytes = new TextEncoder().encode(JSON.stringify(config));
    const cipherBuf = await window.crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      aesKey,
      plainBytes
    );

    const dirRef = doc(dirDb, 'pinotes_account_directory', emailHash);
    await setDoc(
      dirRef,
      {
        ciphertext: bytesToBase64(new Uint8Array(cipherBuf)),
        iv: bytesToBase64(iv),
        salt: bytesToBase64(salt),
        updatedAt: Date.now(),
      },
      { merge: true }
    );
  } catch {
    // Silently ignore if public directory rules aren't enabled
  }
}

export async function lookupEncryptedConfigFromDirectory(
  email: string,
  password: string
): Promise<FirebaseUserConfig | null> {
  if (!window.crypto?.subtle || !email.trim() || !password) return null;
  const dirDb = getRegistryFirestore();
  if (!dirDb) return null;

  try {
    const cleanEmail = email.trim().toLowerCase();
    const emailHash = await sha256Hex(`pinotes_dir_v1::${cleanEmail}`);
    const snap = await getDoc(doc(dirDb, 'pinotes_account_directory', emailHash));
    if (!snap.exists()) return null;

    const data = snap.data() as { ciphertext?: string; iv?: string; salt?: string };
    if (!data.ciphertext || !data.iv || !data.salt) return null;

    const salt = base64ToBytes(data.salt);
    const iv = base64ToBytes(data.iv);
    const cipher = base64ToBytes(data.ciphertext);
    const aesKey = await deriveAesKey(cleanEmail, password, salt);
    const plainBuf = await window.crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      aesKey,
      cipher
    );
    const decoded = JSON.parse(
      new TextDecoder().decode(plainBuf)
    ) as Partial<FirebaseUserConfig>;
    return normalizeRegistryConfig(decoded);
  } catch {
    return null;
  }
}
