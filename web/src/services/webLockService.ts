import { doc, getDocFromServer, onSnapshot, setDoc, Firestore } from 'firebase/firestore';
import { WebPasscodeConfig } from '../types/note';

const LOCK_CACHE_PREFIX = 'pinotes_web_lock_cache_v1_';

function fallbackHash(input: string): string {
  // Deterministic 64-hex fallback if crypto.subtle is unavailable on non-HTTPS origins
  let h1 = 0x811c9dc5;
  let h2 = 0xdeadbeef;
  for (let i = 0; i < input.length; i++) {
    const ch = input.charCodeAt(i);
    h1 ^= ch;
    h1 = Math.imul(h1, 0x01000193);
    h2 ^= ch + i;
    h2 = Math.imul(h2, 0x85ebca6b);
  }
  const part1 = (h1 >>> 0).toString(16).padStart(8, '0');
  const part2 = (h2 >>> 0).toString(16).padStart(8, '0');
  return `fb_${part1}${part2}`;
}

export async function hashWebPasscode(uid: string, passcode: string): Promise<string> {
  const payload = `pinotes_web_lock::${uid}::${passcode}`;
  if (typeof window !== 'undefined' && window.crypto?.subtle) {
    const encoded = new TextEncoder().encode(payload);
    const digest = await window.crypto.subtle.digest('SHA-256', encoded);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  return fallbackHash(payload);
}

export async function verifyWebPasscode(
  uid: string,
  inputPasscode: string,
  storedHash: string | null
): Promise<boolean> {
  if (!storedHash) return false;
  const computed = await hashWebPasscode(uid, inputPasscode);
  return computed === storedHash;
}

export function loadCachedWebLock(uid: string): WebPasscodeConfig | null {
  try {
    const raw = localStorage.getItem(`${LOCK_CACHE_PREFIX}${uid}`);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<WebPasscodeConfig>;
    return {
      enabled: Boolean(parsed.enabled),
      passcodeHash: typeof parsed.passcodeHash === 'string' ? parsed.passcodeHash : null,
      updatedAt: typeof parsed.updatedAt === 'number' ? parsed.updatedAt : 0,
    };
  } catch {
    return null;
  }
}

function saveCachedWebLock(uid: string, cfg: WebPasscodeConfig): void {
  try {
    localStorage.setItem(`${LOCK_CACHE_PREFIX}${uid}`, JSON.stringify(cfg));
  } catch {
    // ignore
  }
}

export function subscribeToWebLock(
  db: Firestore,
  uid: string,
  onUpdate: (cfg: WebPasscodeConfig) => void
) {
  const lockRef = doc(db, 'users', uid, 'config', 'webSecurity');

  // Force a fresh server fetch on mount so changes from other devices reflect immediately
  getDocFromServer(lockRef)
    .then((snap) => {
      if (snap.exists()) {
        const data = snap.data();
        const cfg: WebPasscodeConfig = {
          enabled: Boolean(data.enabled),
          passcodeHash: typeof data.passcodeHash === 'string' ? data.passcodeHash : null,
          updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
        };
        saveCachedWebLock(uid, cfg);
        onUpdate(cfg);
      } else {
        const empty: WebPasscodeConfig = { enabled: false, passcodeHash: null, updatedAt: 0 };
        saveCachedWebLock(uid, empty);
        onUpdate(empty);
      }
    })
    .catch(() => {
      // Fallback to snapshot below if offline
    });

  return onSnapshot(
    lockRef,
    (snap) => {
      if (snap.exists()) {
        const data = snap.data();
        const cfg: WebPasscodeConfig = {
          enabled: Boolean(data.enabled),
          passcodeHash: typeof data.passcodeHash === 'string' ? data.passcodeHash : null,
          updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
        };
        saveCachedWebLock(uid, cfg);
        onUpdate(cfg);
      } else {
        const empty: WebPasscodeConfig = { enabled: false, passcodeHash: null, updatedAt: 0 };
        saveCachedWebLock(uid, empty);
        onUpdate(empty);
      }
    },
    () => {
      // Ignore transient listener error
    }
  );
}

export async function setWebPasscode(
  db: Firestore,
  uid: string,
  passcode: string
): Promise<WebPasscodeConfig> {
  const passcodeHash = await hashWebPasscode(uid, passcode);
  const cfg: WebPasscodeConfig = {
    enabled: true,
    passcodeHash,
    updatedAt: Date.now(),
  };
  const lockRef = doc(db, 'users', uid, 'config', 'webSecurity');
  await setDoc(lockRef, cfg, { merge: true });
  saveCachedWebLock(uid, cfg);
  return cfg;
}

export async function unsetWebPasscode(db: Firestore, uid: string): Promise<WebPasscodeConfig> {
  const cfg: WebPasscodeConfig = {
    enabled: false,
    passcodeHash: null,
    updatedAt: Date.now(),
  };
  const lockRef = doc(db, 'users', uid, 'config', 'webSecurity');
  await setDoc(lockRef, cfg, { merge: true });
  saveCachedWebLock(uid, cfg);
  return cfg;
}
