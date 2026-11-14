import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  deleteDoc,
  getDocs,
  Firestore,
  DocumentData,
} from 'firebase/firestore';
import {
  ref,
  uploadBytesResumable,
  getDownloadURL,
  deleteObject,
  FirebaseStorage,
} from 'firebase/storage';
import {
  DEFAULT_CATEGORIES,
  DEFAULT_TAGS,
  NoteDocument,
  SyncAttachment,
} from '../types/note';

function normalizeAttachment(raw: unknown): SyncAttachment | null {
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  const downloadUrl = typeof obj.downloadUrl === 'string' ? obj.downloadUrl : '';
  const storagePath = typeof obj.storagePath === 'string' ? obj.storagePath : '';
  if (!downloadUrl && !storagePath) return null;
  return {
    fileName: typeof obj.fileName === 'string' ? obj.fileName : 'attachment',
    mimeType: typeof obj.mimeType === 'string' ? obj.mimeType : 'application/octet-stream',
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
  const imageUrlFromField = typeof data.imageUrl === 'string' ? data.imageUrl : null;
  const imageAtts = attachments.filter((a) => a.mimeType.startsWith('image/'));
  const resolvedImageUrl =
    imageUrlFromField ||
    (imageAtts.length > 0 ? imageAtts.map((a) => a.downloadUrl).filter(Boolean).join('|') : null);

  const imageStoragePathFromField =
    typeof data.imageStoragePath === 'string' ? data.imageStoragePath : null;
  const resolvedStoragePath =
    imageStoragePathFromField ||
    (imageAtts.length > 0 ? imageAtts.map((a) => a.storagePath).filter(Boolean).join('|') : null);

  return {
    noteId: typeof data.noteId === 'string' ? data.noteId : id,
    title: typeof data.title === 'string' ? data.title : '',
    content: typeof data.content === 'string' ? data.content : '',
    timestamp: typeof data.timestamp === 'number' ? data.timestamp : now,
    category: typeof data.category === 'string' ? data.category : 'General',
    colorIndex: typeof data.colorIndex === 'number' ? data.colorIndex : 0,
    isPinned: Boolean(data.isPinned),
    attachments,
    isTaskList: Boolean(data.isTaskList),
    webUrl: typeof data.webUrl === 'string' && data.webUrl.trim() ? data.webUrl : null,
    isScheduled: Boolean(data.isScheduled),
    scheduledTime: typeof data.scheduledTime === 'number' ? data.scheduledTime : null,
    repeatFrequency: typeof data.repeatFrequency === 'string' ? data.repeatFrequency : null,
    repeatIntervalMinutes:
      typeof data.repeatIntervalMinutes === 'number' ? data.repeatIntervalMinutes : null,
    repeatUntilTime: typeof data.repeatUntilTime === 'number' ? data.repeatUntilTime : null,
    position: typeof data.position === 'number' ? data.position : 0,
    tags: typeof data.tags === 'string' && data.tags.trim() ? data.tags : null,
    autoTags: typeof data.autoTags === 'string' && data.autoTags.trim() ? data.autoTags : null,
    createdAt: typeof data.createdAt === 'number' ? data.createdAt : now,
    updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : now,
    deletedAt: typeof data.deletedAt === 'number' ? data.deletedAt : null,
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
    category: note.category,
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

export function subscribeToNotes(
  db: Firestore,
  uid: string,
  onNotes: (notes: NoteDocument[]) => void,
  onError: (err: Error) => void
) {
  const notesRef = collection(db, 'users', uid, 'notes');
  return onSnapshot(
    notesRef,
    (snapshot) => {
      const list = snapshot.docs.map((d) => docToNote(d.id, d.data()));
      list.sort((a, b) => {
        if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1;
        return (b.updatedAt || b.timestamp) - (a.updatedAt || a.timestamp);
      });
      onNotes(list);
    },
    (error) => onError(error)
  );
}

export function subscribeToPreferences(
  db: Firestore,
  uid: string,
  onPrefs: (categories: string[], tags: string[]) => void
) {
  const prefRef = doc(db, 'users', uid, 'config', 'preferences');
  return onSnapshot(
    prefRef,
    (snapshot) => {
      if (snapshot.exists()) {
        const data = snapshot.data();
        const cats =
          Array.isArray(data.categories) && data.categories.length > 0
            ? (data.categories as string[])
            : DEFAULT_CATEGORIES;
        const tags =
          Array.isArray(data.tags) && data.tags.length > 0
            ? (data.tags as string[])
            : DEFAULT_TAGS;
        onPrefs(cats, tags);
      } else {
        onPrefs(DEFAULT_CATEGORIES, DEFAULT_TAGS);
      }
    },
    () => {
      onPrefs(DEFAULT_CATEGORIES, DEFAULT_TAGS);
    }
  );
}

export async function refreshNotesOnce(db: Firestore, uid: string): Promise<NoteDocument[]> {
  const notesRef = collection(db, 'users', uid, 'notes');
  const snap = await getDocs(notesRef);
  return snap.docs.map((d) => docToNote(d.id, d.data()));
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

export async function uploadImageForNote(
  storage: FirebaseStorage,
  uid: string,
  noteId: string,
  file: File,
  onProgress?: (pct: number) => void
): Promise<{ downloadUrl: string; storagePath: string; attachment: SyncAttachment }> {
  const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
  const storagePath = `users/${uid}/notes/${noteId}/images/${Date.now()}_${safeName}`;
  const storageRef = ref(storage, storagePath);

  await new Promise<void>((resolve, reject) => {
    const task = uploadBytesResumable(storageRef, file, {
      contentType: file.type || 'image/jpeg',
    });
    task.on(
      'state_changed',
      (snap) => {
        if (onProgress && snap.totalBytes > 0) {
          onProgress(Math.round((snap.bytesTransferred / snap.totalBytes) * 100));
        }
      },
      (err) => reject(err),
      () => resolve()
    );
  });

  const downloadUrl = await getDownloadURL(storageRef);
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
}

export async function uploadFileForNote(
  storage: FirebaseStorage,
  uid: string,
  noteId: string,
  file: File,
  onProgress?: (pct: number) => void
): Promise<SyncAttachment> {
  const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
  const storagePath = `users/${uid}/attachments/${noteId}/${Date.now()}_${safeName}`;
  const storageRef = ref(storage, storagePath);

  await new Promise<void>((resolve, reject) => {
    const task = uploadBytesResumable(storageRef, file, {
      contentType: file.type || 'application/octet-stream',
    });
    task.on(
      'state_changed',
      (snap) => {
        if (onProgress && snap.totalBytes > 0) {
          onProgress(Math.round((snap.bytesTransferred / snap.totalBytes) * 100));
        }
      },
      (err) => reject(err),
      () => resolve()
    );
  });

  const downloadUrl = await getDownloadURL(storageRef);
  return {
    fileName: file.name,
    mimeType: file.type || 'application/octet-stream',
    fileSize: file.size,
    storagePath,
    downloadUrl,
  };
}

export function getNoteImageUrls(note: NoteDocument): string[] {
  const urls = new Set<string>();
  if (note.imageUrl) {
    note.imageUrl
      .split('|')
      .map((u) => u.trim())
      .filter((u) => u.startsWith('http://') || u.startsWith('https://'))
      .forEach((u) => urls.add(u));
  }
  for (const att of note.attachments) {
    if (att.mimeType.startsWith('image/') && att.downloadUrl) {
      urls.add(att.downloadUrl);
    }
  }
  return Array.from(urls);
}

export function getNoteFileAttachments(note: NoteDocument): SyncAttachment[] {
  return note.attachments.filter((att) => !att.mimeType.startsWith('image/'));
}
