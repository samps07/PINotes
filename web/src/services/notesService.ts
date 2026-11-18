import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  deleteDoc,
  getDocs,
  getDocsFromServer,
  getDoc,
  getDocFromServer,
  Firestore,
  DocumentData,
} from 'firebase/firestore';
import {
  ref,
  uploadBytesResumable,
  getDownloadURL,
  deleteObject,
  getStorage,
  FirebaseStorage,
} from 'firebase/storage';
import { FirebaseApp } from 'firebase/app';
import {
  DEFAULT_CATEGORIES,
  DEFAULT_TAGS,
  NoteDocument,
  SyncAttachment,
} from '../types/note';

const IMAGE_EXT_REGEX = /\.(jpe?g|png|gif|webp|bmp|svg|avif|heic)(\?.*)?$/i;

export interface NoteImageEntry {
  url: string;
  storagePath: string;
  fileName: string;
  isLocalOnlyUri: boolean;
}

export interface PreferencesState {
  categories: string[];
  tags: string[];
  hasRemoteDoc: boolean;
  updatedAt: number;
}

const resolvedStorageUrlCache = new Map<string, string>();
const pendingStorageResolutions = new Map<string, Promise<string | null>>();

export function toMillisOrNull(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  if (typeof value === 'object') {
    const obj = value as { toMillis?: () => number; seconds?: number; nanoseconds?: number };
    if (typeof obj.toMillis === 'function') {
      try {
        return obj.toMillis();
      } catch {
        // ignore
      }
    }
    if (typeof obj.seconds === 'number') {
      return Math.round(obj.seconds * 1000 + (obj.nanoseconds || 0) / 1_000_000);
    }
  }
  return null;
}

export function isImageFileOrAttachment(
  fileName?: string | null,
  mimeType?: string | null,
  url?: string | null
): boolean {
  const mime = (mimeType || '').trim().toLowerCase();
  if (mime.startsWith('image/')) return true;
  const u = (url || '').trim().toLowerCase();
  if (u.startsWith('data:image/')) return true;
  if (fileName && IMAGE_EXT_REGEX.test(fileName.trim())) return true;
  if (u && !u.startsWith('data:') && IMAGE_EXT_REGEX.test(u.split('#')[0])) return true;
  return false;
}

export function isWebRenderableUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  const trimmed = url.trim();
  return (
    trimmed.startsWith('https://') ||
    trimmed.startsWith('http://') ||
    trimmed.startsWith('data:') ||
    trimmed.startsWith('blob:')
  );
}

export function formatFileSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function normalizeAttachment(raw: unknown): SyncAttachment | null {
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  const downloadUrl = typeof obj.downloadUrl === 'string' ? obj.downloadUrl.trim() : '';
  const storagePath = typeof obj.storagePath === 'string' ? obj.storagePath.trim() : '';
  const fileName =
    typeof obj.fileName === 'string' && obj.fileName.trim()
      ? obj.fileName.trim()
      : storagePath.split('/').pop() || 'attachment';
  if (!downloadUrl && !storagePath) return null;

  const rawMime =
    typeof obj.mimeType === 'string' && obj.mimeType.trim()
      ? obj.mimeType.trim()
      : 'application/octet-stream';

  const mimeType =
    rawMime === 'application/octet-stream' && isImageFileOrAttachment(fileName, rawMime, downloadUrl)
      ? 'image/jpeg'
      : rawMime;

  return {
    fileName,
    mimeType,
    fileSize: typeof obj.fileSize === 'number' ? obj.fileSize : 0,
    storagePath,
    downloadUrl,
  };
}

export function docToNote(id: string, data: DocumentData): NoteDocument {
  const attachmentsRaw = Array.isArray(data.attachments) ? data.attachments : [];
  const attachments = attachmentsRaw
    .map(normalizeAttachment)
    .filter((a): a is SyncAttachment => a !== null);

  const now = Date.now();
  const imageUrlFromField =
    typeof data.imageUrl === 'string' && data.imageUrl.trim() ? data.imageUrl.trim() : null;
  const imageAtts = attachments.filter((a) =>
    isImageFileOrAttachment(a.fileName, a.mimeType, a.downloadUrl)
  );

  const resolvedImageUrl =
    imageUrlFromField ||
    (imageAtts.length > 0 ? imageAtts.map((a) => a.downloadUrl).filter(Boolean).join('|') : null);

  const imageStoragePathFromField =
    typeof data.imageStoragePath === 'string' && data.imageStoragePath.trim()
      ? data.imageStoragePath.trim()
      : null;
  const resolvedStoragePath =
    imageStoragePathFromField ||
    (imageAtts.length > 0 ? imageAtts.map((a) => a.storagePath).filter(Boolean).join('|') : null);

  const rawDeletedAt = toMillisOrNull(data.deletedAt);
  const deletedAt = rawDeletedAt !== null && rawDeletedAt > 0 ? rawDeletedAt : null;

  const rawCategory = typeof data.category === 'string' ? data.category.trim() : '';
  const category = rawCategory || 'General';

  return {
    noteId: typeof data.noteId === 'string' && data.noteId.trim() ? data.noteId : id,
    title: typeof data.title === 'string' ? data.title : '',
    content: typeof data.content === 'string' ? data.content : '',
    timestamp: toMillisOrNull(data.timestamp) ?? now,
    category,
    colorIndex: typeof data.colorIndex === 'number' ? data.colorIndex : 0,
    isPinned: Boolean(data.isPinned),
    attachments,
    isTaskList: Boolean(data.isTaskList),
    webUrl: typeof data.webUrl === 'string' && data.webUrl.trim() ? data.webUrl.trim() : null,
    isScheduled: Boolean(data.isScheduled),
    scheduledTime: toMillisOrNull(data.scheduledTime),
    repeatFrequency: typeof data.repeatFrequency === 'string' ? data.repeatFrequency : null,
    repeatIntervalMinutes:
      typeof data.repeatIntervalMinutes === 'number' ? data.repeatIntervalMinutes : null,
    repeatUntilTime: toMillisOrNull(data.repeatUntilTime),
    position: typeof data.position === 'number' ? data.position : 0,
    tags: typeof data.tags === 'string' && data.tags.trim() ? data.tags.trim() : null,
    autoTags: typeof data.autoTags === 'string' && data.autoTags.trim() ? data.autoTags.trim() : null,
    createdAt: toMillisOrNull(data.createdAt) ?? now,
    updatedAt: toMillisOrNull(data.updatedAt) ?? now,
    deletedAt,
    imageUrl: resolvedImageUrl,
    imageStoragePath: resolvedStoragePath,
    isPersistentNotification: Boolean(data.isPersistentNotification),
    isArchived: Boolean(data.isArchived),
  };
}

export function noteToFirestoreMap(note: NoteDocument): Record<string, unknown> {
  return {
    noteId: note.noteId,
    title: note.title,
    content: note.content,
    timestamp: note.timestamp,
    category: note.category?.trim() || 'General',
    colorIndex: note.colorIndex,
    isPinned: note.isPinned,
    attachments: note.attachments,
    isTaskList: note.isTaskList,
    webUrl: note.webUrl,
    isScheduled: note.isScheduled,
    scheduledTime: note.scheduledTime,
    repeatFrequency: note.repeatFrequency,
    repeatIntervalMinutes: note.repeatIntervalMinutes,
    repeatUntilTime: note.repeatUntilTime,
    position: note.position,
    tags: note.tags,
    autoTags: note.autoTags,
    createdAt: note.createdAt,
    updatedAt: note.updatedAt,
    deletedAt: note.deletedAt,
    imageUrl: note.imageUrl,
    imageStoragePath: note.imageStoragePath,
    isPersistentNotification: note.isPersistentNotification,
    isArchived: note.isArchived,
  };
}

function sortNotesList(list: NoteDocument[]): NoteDocument[] {
  return [...list].sort((a, b) => {
    if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1;
    return (b.updatedAt || b.timestamp) - (a.updatedAt || a.timestamp);
  });
}

export function subscribeToNotes(
  db: Firestore,
  uid: string,
  onNotes: (notes: NoteDocument[], fromCache?: boolean) => void,
  onError: (err: Error) => void
) {
  const notesRef = collection(db, 'users', uid, 'notes');

  // Trigger an immediate server read so stale IndexedDB persistence never hides newly synced Android notes
  getDocsFromServer(notesRef)
    .then((snap) => {
      const list = sortNotesList(snap.docs.map((d) => docToNote(d.id, d.data())));
      onNotes(list, false);
    })
    .catch(() => {
      // Offline or transient error: onSnapshot cache/reconnect below handles it
    });

  return onSnapshot(
    notesRef,
    { includeMetadataChanges: true },
    (snapshot) => {
      const list = sortNotesList(snapshot.docs.map((d) => docToNote(d.id, d.data())));
      onNotes(list, snapshot.metadata.fromCache);
    },
    (error) => onError(error)
  );
}

function parsePreferencesSnapshot(data: DocumentData | undefined): PreferencesState {
  if (!data) {
    return {
      categories: DEFAULT_CATEGORIES,
      tags: DEFAULT_TAGS,
      hasRemoteDoc: false,
      updatedAt: 0,
    };
  }
  const rawCats = Array.isArray(data.categories)
    ? data.categories.filter((c): c is string => typeof c === 'string' && c.trim().length > 0).map((c) => c.trim())
    : [];
  const rawTags = Array.isArray(data.tags)
    ? data.tags.filter((t): t is string => typeof t === 'string' && t.trim().length > 0).map((t) => t.trim().replace(/^#/, ''))
    : [];
  return {
    categories: rawCats.length > 0 ? rawCats : DEFAULT_CATEGORIES,
    tags: rawTags.length > 0 ? rawTags : DEFAULT_TAGS,
    hasRemoteDoc: true,
    updatedAt: toMillisOrNull(data.updatedAt) ?? 0,
  };
}

export function subscribeToPreferences(
  db: Firestore,
  uid: string,
  onPrefs: (prefs: PreferencesState) => void
) {
  const prefRef = doc(db, 'users', uid, 'config', 'preferences');

  // Force fresh server fetch so category/tag edits made in Android app reflect immediately
  getDocFromServer(prefRef)
    .then((snap) => {
      onPrefs(parsePreferencesSnapshot(snap.exists() ? snap.data() : undefined));
    })
    .catch(() => {
      // Fallback to snapshot below if offline
    });

  return onSnapshot(
    prefRef,
    { includeMetadataChanges: true },
    (snapshot) => {
      onPrefs(parsePreferencesSnapshot(snapshot.exists() ? snapshot.data() : undefined));
    },
    () => {
      onPrefs(parsePreferencesSnapshot(undefined));
    }
  );
}

export async function refreshNotesOnce(db: Firestore, uid: string): Promise<NoteDocument[]> {
  const notesRef = collection(db, 'users', uid, 'notes');
  try {
    const snap = await getDocsFromServer(notesRef);
    return sortNotesList(snap.docs.map((d) => docToNote(d.id, d.data())));
  } catch {
    const snap = await getDocs(notesRef);
    return sortNotesList(snap.docs.map((d) => docToNote(d.id, d.data())));
  }
}

export async function refreshPreferencesOnce(
  db: Firestore,
  uid: string
): Promise<PreferencesState> {
  const prefRef = doc(db, 'users', uid, 'config', 'preferences');
  try {
    const snap = await getDocFromServer(prefRef);
    return parsePreferencesSnapshot(snap.exists() ? snap.data() : undefined);
  } catch {
    const snap = await getDoc(prefRef);
    return parsePreferencesSnapshot(snap.exists() ? snap.data() : undefined);
  }
}

export async function saveNoteToFirestore(
  db: Firestore,
  uid: string,
  note: NoteDocument
): Promise<void> {
  const noteRef = doc(db, 'users', uid, 'notes', note.noteId);
  await setDoc(noteRef, noteToFirestoreMap(note), { merge: true });
}

export async function savePreferencesToFirestore(
  db: Firestore,
  uid: string,
  categories: string[],
  tags: string[]
): Promise<void> {
  const prefRef = doc(db, 'users', uid, 'config', 'preferences');
  await setDoc(
    prefRef,
    {
      categories,
      tags,
      updatedAt: Date.now(),
    },
    { merge: true }
  );
}

export async function resolveStorageDownloadUrl(
  storage: FirebaseStorage | null | undefined,
  storagePath: string | null | undefined,
  firebaseApp?: FirebaseApp | null
): Promise<string | null> {
  const cleanPath = (storagePath || '').trim();
  if (!cleanPath || !storage) return null;

  const cached = resolvedStorageUrlCache.get(cleanPath);
  if (cached) return cached;

  const inflight = pendingStorageResolutions.get(cleanPath);
  if (inflight) return inflight;

  const promise = (async () => {
    try {
      const url = await getDownloadURL(ref(storage, cleanPath));
      resolvedStorageUrlCache.set(cleanPath, url);
      return url;
    } catch {
      if (firebaseApp) {
        const configuredBucket = firebaseApp.options.storageBucket || '';
        const projectId = firebaseApp.options.projectId || '';
        const altBucket = configuredBucket.endsWith('.firebasestorage.app')
          ? `${projectId}.appspot.com`
          : `${projectId}.firebasestorage.app`;

        if (projectId && altBucket !== configuredBucket) {
          try {
            const altStorage = getStorage(firebaseApp, `gs://${altBucket}`);
            const altUrl = await getDownloadURL(ref(altStorage, cleanPath));
            resolvedStorageUrlCache.set(cleanPath, altUrl);
            return altUrl;
          } catch {
            // ignore
          }
        }
      }
      return null;
    } finally {
      pendingStorageResolutions.delete(cleanPath);
    }
  })();

  pendingStorageResolutions.set(cleanPath, promise);
  return promise;
}

export function dataUrlToBlob(dataUrl: string): Blob | null {
  try {
    const commaIdx = dataUrl.indexOf(',');
    if (commaIdx === -1) return null;
    const header = dataUrl.slice(0, commaIdx);
    const body = dataUrl.slice(commaIdx + 1);
    const mimeMatch = header.match(/data:([^;]+)/i);
    const mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';

    if (/;base64/i.test(header)) {
      const bin = atob(body);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) {
        bytes[i] = bin.charCodeAt(i);
      }
      return new Blob([bytes], { type: mime });
    }
    return new Blob([decodeURIComponent(body)], { type: mime });
  } catch {
    return null;
  }
}

export async function triggerAttachmentDownload(
  att: { fileName: string; downloadUrl: string; storagePath?: string },
  storage?: FirebaseStorage | null,
  firebaseApp?: FirebaseApp | null
): Promise<void> {
  let targetUrl = att.downloadUrl;
  if ((!targetUrl || !isWebRenderableUrl(targetUrl)) && att.storagePath) {
    const resolved = await resolveStorageDownloadUrl(storage, att.storagePath, firebaseApp);
    if (resolved) targetUrl = resolved;
  }
  if (!targetUrl) return;

  if (targetUrl.startsWith('data:')) {
    const blob = dataUrlToBlob(targetUrl);
    if (blob) {
      const objectUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = att.fileName || 'attachment';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 10000);
      return;
    }
  }

  const a = document.createElement('a');
  a.href = targetUrl;
  a.download = att.fileName || 'attachment';
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

export async function deleteStoragePathSafe(
  storage: FirebaseStorage,
  path: string
): Promise<void> {
  if (!path.trim()) return;
  try {
    await deleteObject(ref(storage, path));
  } catch {
    // Ignore if already deleted
  }
}

export async function permanentlyDeleteNote(
  db: Firestore,
  storage: FirebaseStorage,
  uid: string,
  note: NoteDocument
): Promise<void> {
  for (const att of note.attachments) {
    if (att.storagePath) {
      await deleteStoragePathSafe(storage, att.storagePath);
    }
  }
  if (note.imageStoragePath) {
    const paths = note.imageStoragePath
      .split('|')
      .map((p) => p.trim())
      .filter(Boolean);
    for (const p of paths) {
      await deleteStoragePathSafe(storage, p);
    }
  }
  await deleteDoc(doc(db, 'users', uid, 'notes', note.noteId));
}

async function uploadToBucketWithTimeout(
  targetStorage: FirebaseStorage,
  storagePath: string,
  file: File,
  onProgress?: (pct: number) => void,
  timeoutMs = 12000
): Promise<string> {
  const storageRef = ref(targetStorage, storagePath);

  await new Promise<void>((resolve, reject) => {
    const task = uploadBytesResumable(storageRef, file, {
      contentType: file.type || 'application/octet-stream',
    });

    const timer = setTimeout(() => {
      try {
        task.cancel();
      } catch {
        // ignore cancel error
      }
      reject(new Error('Firebase Storage upload timed out (CORS or unreachable bucket).'));
    }, timeoutMs);

    task.on(
      'state_changed',
      (snap) => {
        if (onProgress && snap.totalBytes > 0) {
          onProgress(Math.round((snap.bytesTransferred / snap.totalBytes) * 100));
        }
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
      () => {
        clearTimeout(timer);
        resolve();
      }
    );
  });

  return getDownloadURL(storageRef);
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Failed to read file.'));
    reader.onload = () => resolve(String(reader.result || ''));
    reader.readAsDataURL(file);
  });
}

/**
 * Compresses an image in the browser via Canvas to a compact JPEG data URL
 * so image notes still succeed and sync via Firestore even if Firebase Storage
 * CORS or Spark-plan Storage restrictions block direct bucket uploads.
 */
async function compressImageToDataUrl(file: File, maxDim = 1024, maxChars = 420000): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Failed to read image file.'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Invalid or unsupported image file.'));
      img.onload = () => {
        let width = img.naturalWidth || img.width;
        let height = img.naturalHeight || img.height;

        if (width > maxDim || height > maxDim) {
          const ratio = Math.min(maxDim / width, maxDim / height);
          width = Math.max(1, Math.round(width * ratio));
          height = Math.max(1, Math.round(height * ratio));
        }

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          reject(new Error('Canvas context unavailable.'));
          return;
        }
        ctx.drawImage(img, 0, 0, width, height);

        let quality = 0.78;
        let dataUrl = canvas.toDataURL('image/jpeg', quality);
        while (dataUrl.length > maxChars && quality > 0.35) {
          quality -= 0.12;
          dataUrl = canvas.toDataURL('image/jpeg', quality);
        }

        if (dataUrl.length > maxChars) {
          const scale = 0.65;
          canvas.width = Math.max(1, Math.round(width * scale));
          canvas.height = Math.max(1, Math.round(height * scale));
          ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
          dataUrl = canvas.toDataURL('image/jpeg', 0.6);
        }

        resolve(dataUrl);
      };
      img.src = reader.result as string;
    };
    reader.readAsDataURL(file);
  });
}

export async function uploadImageForNote(
  storage: FirebaseStorage,
  uid: string,
  noteId: string,
  file: File,
  onProgress?: (pct: number) => void,
  firebaseApp?: FirebaseApp | null
): Promise<{
  downloadUrl: string;
  storagePath: string;
  attachment: SyncAttachment;
  usedInlineFallback?: boolean;
}> {
  const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
  const storagePath = `users/${uid}/notes/${noteId}/images/${Date.now()}_${safeName}`;

  try {
    const downloadUrl = await uploadToBucketWithTimeout(storage, storagePath, file, onProgress);
    return {
      downloadUrl,
      storagePath,
      attachment: {
        fileName: file.name,
        mimeType: file.type || 'image/jpeg',
        fileSize: file.size,
        storagePath,
        downloadUrl,
      },
    };
  } catch (primaryErr) {
    if (firebaseApp) {
      const configuredBucket = firebaseApp.options.storageBucket || '';
      const projectId = firebaseApp.options.projectId || '';
      const altBucket = configuredBucket.endsWith('.firebasestorage.app')
        ? `${projectId}.appspot.com`
        : `${projectId}.firebasestorage.app`;

      if (projectId && altBucket !== configuredBucket) {
        try {
          const altStorage = getStorage(firebaseApp, `gs://${altBucket}`);
          const downloadUrl = await uploadToBucketWithTimeout(
            altStorage,
            storagePath,
            file,
            onProgress
          );
          return {
            downloadUrl,
            storagePath,
            attachment: {
              fileName: file.name,
              mimeType: file.type || 'image/jpeg',
              fileSize: file.size,
              storagePath,
              downloadUrl,
            },
          };
        } catch {
          // Proceed to inline compressed fallback
        }
      }
    }

    console.warn(
      'Firebase Storage upload failed, falling back to compressed Firestore image:',
      primaryErr
    );

    const dataUrl = await compressImageToDataUrl(file);
    return {
      downloadUrl: dataUrl,
      storagePath: '',
      usedInlineFallback: true,
      attachment: {
        fileName: file.name,
        mimeType: 'image/jpeg',
        fileSize: dataUrl.length,
        storagePath: '',
        downloadUrl: dataUrl,
      },
    };
  }
}

export async function uploadFileForNote(
  storage: FirebaseStorage,
  uid: string,
  noteId: string,
  file: File,
  onProgress?: (pct: number) => void,
  firebaseApp?: FirebaseApp | null
): Promise<SyncAttachment> {
  const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
  const storagePath = `users/${uid}/attachments/${noteId}/${Date.now()}_${safeName}`;

  try {
    const downloadUrl = await uploadToBucketWithTimeout(storage, storagePath, file, onProgress, 18000);
    return {
      fileName: file.name,
      mimeType: file.type || 'application/octet-stream',
      fileSize: file.size,
      storagePath,
      downloadUrl,
    };
  } catch (primaryErr) {
    if (firebaseApp) {
      const configuredBucket = firebaseApp.options.storageBucket || '';
      const projectId = firebaseApp.options.projectId || '';
      const altBucket = configuredBucket.endsWith('.firebasestorage.app')
        ? `${projectId}.appspot.com`
        : `${projectId}.firebasestorage.app`;

      if (projectId && altBucket !== configuredBucket) {
        try {
          const altStorage = getStorage(firebaseApp, `gs://${altBucket}`);
          const downloadUrl = await uploadToBucketWithTimeout(
            altStorage,
            storagePath,
            file,
            onProgress,
            18000
          );
          return {
            fileName: file.name,
            mimeType: file.type || 'application/octet-stream',
            fileSize: file.size,
            storagePath,
            downloadUrl,
          };
        } catch {
          // fall through
        }
      }
    }

    // Small file fallback (< 380 KB raw -> < 515 KB base64) can still sync cleanly inside Firestore
    if (file.size <= 380 * 1024) {
      const dataUrl = await readFileAsDataUrl(file);
      return {
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        fileSize: file.size,
        storagePath: '',
        downloadUrl: dataUrl,
      };
    }

    throw primaryErr instanceof Error
      ? primaryErr
      : new Error('File upload failed (Firebase Storage unreachable or file > 380 KB without Storage CORS).');
  }
}

export function getNoteImageEntries(note: NoteDocument): NoteImageEntry[] {
  const entries: NoteImageEntry[] = [];
  const seenUrls = new Set<string>();
  const seenPaths = new Set<string>();

  const rawUrls = note.imageUrl
    ? note.imageUrl.split('|').map((u) => u.trim()).filter(Boolean)
    : [];
  const rawPaths = note.imageStoragePath
    ? note.imageStoragePath.split('|').map((p) => p.trim()).filter(Boolean)
    : [];

  const maxLen = Math.max(rawUrls.length, rawPaths.length);
  for (let i = 0; i < maxLen; i++) {
    const rawUrl = rawUrls[i] || '';
    const storagePath = rawPaths[i] || '';
    const renderable = isWebRenderableUrl(rawUrl);

    if (!renderable && !storagePath) {
      if (rawUrl && (rawUrl.startsWith('content://') || rawUrl.startsWith('file://'))) {
        // Check if an attachment matches by filename so we recover its storagePath/downloadUrl
        const hintName = rawUrl.split('/').pop() || '';
        const matched = note.attachments.find(
          (a) => a.fileName === hintName || a.storagePath.endsWith(`/${hintName}`)
        );
        if (matched && (matched.downloadUrl || matched.storagePath)) {
          const mRenderable = isWebRenderableUrl(matched.downloadUrl);
          const url = mRenderable ? matched.downloadUrl : '';
          if (url) seenUrls.add(url);
          if (matched.storagePath) seenPaths.add(matched.storagePath);
          entries.push({
            url,
            storagePath: matched.storagePath,
            fileName: matched.fileName,
            isLocalOnlyUri: !url && !matched.storagePath,
          });
          continue;
        }
        entries.push({
          url: '',
          storagePath: '',
          fileName: hintName || 'Android Local Image',
          isLocalOnlyUri: true,
        });
      }
      continue;
    }

    const url = renderable ? rawUrl : '';
    if (url && seenUrls.has(url)) continue;
    if (storagePath && seenPaths.has(storagePath)) continue;

    if (url) seenUrls.add(url);
    if (storagePath) seenPaths.add(storagePath);

    entries.push({
      url,
      storagePath,
      fileName:
        storagePath.split('/').pop()?.replace(/^\d+_/, '') ||
        (url && !url.startsWith('data:') ? url.split('/').pop()?.split('?')[0] : '') ||
        `Image ${entries.length + 1}`,
      isLocalOnlyUri: false,
    });
  }

  for (const att of note.attachments) {
    if (!isImageFileOrAttachment(att.fileName, att.mimeType, att.downloadUrl)) continue;
    const renderable = isWebRenderableUrl(att.downloadUrl);
    const url = renderable ? att.downloadUrl : '';

    if (url && seenUrls.has(url)) continue;
    if (att.storagePath && seenPaths.has(att.storagePath)) continue;
    if (!url && !att.storagePath) continue;

    if (url) seenUrls.add(url);
    if (att.storagePath) seenPaths.add(att.storagePath);

    entries.push({
      url,
      storagePath: att.storagePath,
      fileName: att.fileName,
      isLocalOnlyUri: false,
    });
  }

  return entries;
}

export function getNoteImageUrls(note: NoteDocument): string[] {
  return getNoteImageEntries(note)
    .map((e) => e.url)
    .filter(Boolean);
}

export function getNoteFileAttachments(note: NoteDocument): SyncAttachment[] {
  return note.attachments.filter(
    (att) => !isImageFileOrAttachment(att.fileName, att.mimeType, att.downloadUrl)
  );
}
