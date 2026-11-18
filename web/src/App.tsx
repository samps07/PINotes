import { useEffect, useMemo, useState, useCallback } from 'react';
import { FirebaseApp } from 'firebase/app';
import {
  onAuthStateChanged,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  User,
} from 'firebase/auth';
import { Firestore } from 'firebase/firestore';
import { FirebaseStorage } from 'firebase/storage';
import { Loader2, StickyNote, Trash2 } from 'lucide-react';
import {
  DEFAULT_CATEGORIES,
  DEFAULT_TAGS,
  FirebaseUserConfig,
  NoteDocument,
  SidebarSection,
  SyncStatus,
  WebPasscodeConfig,
} from './types/note';
import {
  clearSavedFirebaseConfig,
  getOrInitFirebaseInstances,
  loadSavedFirebaseConfig,
  saveFirebaseConfig,
  teardownFirebaseInstance,
} from './utils/firebaseClient';
import {
  permanentlyDeleteNote,
  refreshNotesOnce,
  refreshPreferencesOnce,
  saveNoteToFirestore,
  savePreferencesToFirestore,
  subscribeToNotes,
  subscribeToPreferences,
  uploadFileForNote,
  uploadImageForNote,
  deleteStoragePathSafe,
} from './services/notesService';
import {
  loadCachedWebLock,
  setWebPasscode,
  subscribeToWebLock,
  unsetWebPasscode,
  verifyWebPasscode,
} from './services/webLockService';
import {
  saveAccountFirebaseConfigToUserDoc,
  saveEncryptedConfigToDirectory,
} from './services/accountConfigService';
import { stripRichTagsToPlainText } from './utils/richText';
import { SetupAuthScreen } from './components/SetupAuthScreen';
import { PasscodeLockScreen } from './components/PasscodeLockScreen';
import { TopBar } from './components/TopBar';
import { Sidebar } from './components/Sidebar';
import { QuickCaptureBar } from './components/QuickCaptureBar';
import { NoteCard } from './components/NoteCard';
import { NoteEditorModal } from './components/NoteEditorModal';
import { AttachmentPreviewModal, PreviewTarget } from './components/AttachmentPreviewModal';
import { SettingsModal } from './components/SettingsModal';

const THEME_KEY = 'pinotes_theme_v1';
const VIEW_KEY = 'pinotes_grid_view_v1';

function splitNoteTags(raw: string | null | undefined): string[] {
  if (!raw) return [];
  return raw
    .split(/[|,]+/)
    .map((t) => t.trim().replace(/^#/, '').trim())
    .filter(Boolean);
}

export function App() {
  const [isDark, setIsDark] = useState<boolean>(() => {
    const saved = localStorage.getItem(THEME_KEY);
    return saved ? saved === 'dark' : true;
  });

  const [isGridView, setIsGridView] = useState<boolean>(() => {
    const saved = localStorage.getItem(VIEW_KEY);
    return saved ? saved === 'grid' : true;
  });

  const [firebaseConfig, setFirebaseConfig] = useState<FirebaseUserConfig | null>(() =>
    loadSavedFirebaseConfig()
  );
  const [firebaseApp, setFirebaseApp] = useState<FirebaseApp | null>(null);
  const [db, setDb] = useState<Firestore | null>(null);
  const [storage, setStorage] = useState<FirebaseStorage | null>(null);
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [booting, setBooting] = useState<boolean>(() => Boolean(loadSavedFirebaseConfig()));

  // Web-only cross-session passcode lock (in-memory unlock resets on every refresh)
  const [webLock, setWebLock] = useState<WebPasscodeConfig>({
    enabled: false,
    passcodeHash: null,
    updatedAt: 0,
  });
  const [webLockLoading, setWebLockLoading] = useState<boolean>(false);
  const [isWebUnlocked, setIsWebUnlocked] = useState<boolean>(false);

  const [notes, setNotes] = useState<NoteDocument[]>([]);
  const [prefCategories, setPrefCategories] = useState<string[]>(DEFAULT_CATEGORIES);
  const [prefTags, setPrefTags] = useState<string[]>(DEFAULT_TAGS);
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('OFFLINE');

  const [sidebarOpen, setSidebarOpen] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.innerWidth >= 1024 : true
  );
  const [activeSection, setActiveSection] = useState<SidebarSection>({ type: 'notes' });
  const [searchQuery, setSearchQuery] = useState('');

  const [editingNote, setEditingNote] = useState<NoteDocument | null>(null);
  const [previewTarget, setPreviewTarget] = useState<PreviewTarget | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [toastMsg, setToastMsg] = useState<string | null>(null);

  const notify = useCallback((msg: string) => {
    setToastMsg(msg);
  }, []);

  useEffect(() => {
    if (!toastMsg) return;
    const t = setTimeout(() => setToastMsg(null), 2800);
    return () => clearTimeout(t);
  }, [toastMsg]);

  useEffect(() => {
    const root = document.documentElement;
    if (isDark) root.classList.add('dark');
    else root.classList.remove('dark');
    localStorage.setItem(THEME_KEY, isDark ? 'dark' : 'light');
  }, [isDark]);

  useEffect(() => {
    localStorage.setItem(VIEW_KEY, isGridView ? 'grid' : 'list');
  }, [isGridView]);

  // Auto-reconnect on boot if config is saved
  useEffect(() => {
    if (!firebaseConfig) {
      setBooting(false);
      return;
    }

    let unsubAuth: (() => void) | undefined;
    let cancelled = false;

    (async () => {
      try {
        const instances = await getOrInitFirebaseInstances(firebaseConfig);
        if (cancelled) return;
        setFirebaseApp(instances.app);
        setDb(instances.db);
        setStorage(instances.storage);

        unsubAuth = onAuthStateChanged(instances.auth, (user) => {
          if (cancelled) return;
          setCurrentUser(user);
          if (user) {
            const cached = loadCachedWebLock(user.uid);
            if (cached) setWebLock(cached);
            setWebLockLoading(true);
            saveFirebaseConfig(firebaseConfig, user.email || undefined);
          } else {
            setIsWebUnlocked(false);
            setWebLock({ enabled: false, passcodeHash: null, updatedAt: 0 });
            setWebLockLoading(false);
          }
          setBooting(false);
          setSyncStatus(user ? 'SYNCED' : 'OFFLINE');
        });
      } catch {
        if (!cancelled) {
          setBooting(false);
          setSyncStatus('ERROR');
        }
      }
    })();

    return () => {
      cancelled = true;
      unsubAuth?.();
    };
  }, [firebaseConfig]);

  // Subscribe in real time to Firestore notes, preferences, & web security lock
  useEffect(() => {
    if (!db || !currentUser) {
      setNotes([]);
      return;
    }

    setSyncStatus('SYNCING');
    const unsubNotes = subscribeToNotes(
      db,
      currentUser.uid,
      (loaded) => {
        setNotes(loaded);
        setSyncStatus(navigator.onLine ? 'SYNCED' : 'OFFLINE');
      },
      (err) => {
        console.error(err);
        setSyncStatus('ERROR');
        notify('Firestore sync error: check security rules or permissions.');
      }
    );

    const unsubPrefs = subscribeToPreferences(db, currentUser.uid, (prefs) => {
      setPrefCategories(prefs.categories);
      setPrefTags(prefs.tags);
    });

    const unsubLock = subscribeToWebLock(db, currentUser.uid, (cfg) => {
      setWebLock(cfg);
      setWebLockLoading(false);
    });

    return () => {
      unsubNotes();
      unsubPrefs();
      unsubLock();
    };
  }, [db, currentUser, notify]);

  // Merge Firestore preferences with any categories/tags discovered directly on synced notes in real time
  const categories = useMemo(() => {
    const result: string[] = [];
    const seenLower = new Set<string>();
    const addUnique = (raw: string | null | undefined) => {
      const clean = (raw || '').trim();
      if (!clean) return;
      const lower = clean.toLowerCase();
      if (!seenLower.has(lower)) {
        seenLower.add(lower);
        result.push(clean);
      }
    };

    prefCategories.forEach(addUnique);
    notes.forEach((n) => {
      if (n.deletedAt === null) addUnique(n.category);
    });
    if (!seenLower.has('general')) {
      result.unshift('General');
    }
    return result;
  }, [prefCategories, notes]);

  const tags = useMemo(() => {
    const result: string[] = [];
    const seenLower = new Set<string>();
    const addUnique = (raw: string | null | undefined) => {
      const clean = (raw || '').trim().replace(/^#/, '').trim();
      if (!clean) return;
      const lower = clean.toLowerCase();
      if (!seenLower.has(lower)) {
        seenLower.add(lower);
        result.push(clean);
      }
    };

    prefTags.forEach(addUnique);
    notes.forEach((n) => {
      if (n.deletedAt === null) {
        splitNoteTags(n.tags).forEach(addUnique);
        splitNoteTags(n.autoTags).forEach(addUnique);
      }
    });
    return result;
  }, [prefTags, notes]);

  const handleConnect = async (
    config: FirebaseUserConfig,
    email: string,
    password: string,
    mode: 'signin' | 'signup'
  ) => {
    const saved = saveFirebaseConfig(config, email);
    const instances = await getOrInitFirebaseInstances(saved);

    const cred =
      mode === 'signin'
        ? await signInWithEmailAndPassword(instances.auth, email, password)
        : await createUserWithEmailAndPassword(instances.auth, email, password);

    setFirebaseApp(instances.app);
    setDb(instances.db);
    setStorage(instances.storage);
    setFirebaseConfig(saved);

    if (cred.user) {
      void saveAccountFirebaseConfigToUserDoc(instances.db, cred.user.uid, email, saved);
      void saveEncryptedConfigToDirectory(email, password, saved);
    }
  };

  const handleManualSync = async () => {
    if (!db || !currentUser) return;
    setSyncStatus('SYNCING');
    try {
      const [freshNotes, freshPrefs] = await Promise.all([
        refreshNotesOnce(db, currentUser.uid),
        refreshPreferencesOnce(db, currentUser.uid),
      ]);
      setNotes(freshNotes);
      setPrefCategories(freshPrefs.categories);
      setPrefTags(freshPrefs.tags);
      setSyncStatus('SYNCED');
      notify('Notes, categories & tags synced from cloud');
    } catch {
      setSyncStatus('ERROR');
      notify('Failed to refresh notes');
    }
  };

  const handleSignOut = async () => {
    if (firebaseConfig) {
      const instances = await getOrInitFirebaseInstances(firebaseConfig);
      await signOut(instances.auth);
    }
    setSettingsOpen(false);
    setIsWebUnlocked(false);
    setWebLock({ enabled: false, passcodeHash: null, updatedAt: 0 });
    setCurrentUser(null);
    setNotes([]);
    setSyncStatus('OFFLINE');
  };

  const handleForgetConfigAndSignOut = async () => {
    await handleSignOut();
    await teardownFirebaseInstance();
    clearSavedFirebaseConfig();
    setFirebaseConfig(null);
    setFirebaseApp(null);
    setDb(null);
    setStorage(null);
  };

  const handleSetWebPasscode = async (passcode: string) => {
    if (!db || !currentUser) return;
    const cfg = await setWebPasscode(db, currentUser.uid, passcode);
    setWebLock(cfg);
    setIsWebUnlocked(true);
    notify('Web app passcode lock enabled');
  };

  const handleResetWebPasscode = async (
    currentPasscode: string,
    newPasscode: string
  ): Promise<boolean> => {
    if (!db || !currentUser) return false;
    const ok = await verifyWebPasscode(currentUser.uid, currentPasscode, webLock.passcodeHash);
    if (!ok) return false;
    const cfg = await setWebPasscode(db, currentUser.uid, newPasscode);
    setWebLock(cfg);
    setIsWebUnlocked(true);
    notify('Web app passcode updated');
    return true;
  };

  const handleUnsetWebPasscode = async (currentPasscode: string): Promise<boolean> => {
    if (!db || !currentUser) return false;
    const ok = await verifyWebPasscode(currentUser.uid, currentPasscode, webLock.passcodeHash);
    if (!ok) return false;
    const cfg = await unsetWebPasscode(db, currentUser.uid);
    setWebLock(cfg);
    setIsWebUnlocked(false);
    notify('Web app passcode lock removed');
    return true;
  };

  const handleLockNow = () => {
    setSettingsOpen(false);
    setIsWebUnlocked(false);
  };

  const handleEnsureCategory = async (name: string) => {
    const clean = name.trim();
    if (!clean || !db || !currentUser) return;
    if (prefCategories.some((c) => c.toLowerCase() === clean.toLowerCase())) return;
    const next = [...categories];
    if (!next.some((c) => c.toLowerCase() === clean.toLowerCase())) {
      next.push(clean);
    }
    setPrefCategories(next);
    await savePreferencesToFirestore(db, currentUser.uid, next, prefTags);
  };

  const handleCreateNote = async (
    draft: Partial<NoteDocument>,
    imageFiles: File[] = [],
    otherFiles: File[] = []
  ) => {
    if (!db || !storage || !currentUser) return;
    const now = Date.now();
    const noteId = crypto.randomUUID ? crypto.randomUUID() : `web_${now}_${Math.random()}`;

    const uploadedAttachments = [];
    const uploadedUrls: string[] = draft.imageUrl
      ? draft.imageUrl.split('|').map((u) => u.trim()).filter(Boolean)
      : [];
    const uploadedPaths: string[] = [];
    let usedInlineFallback = false;

    for (const file of imageFiles) {
      const res = await uploadImageForNote(
        storage,
        currentUser.uid,
        noteId,
        file,
        undefined,
        firebaseApp
      );
      if (res.usedInlineFallback) usedInlineFallback = true;
      uploadedAttachments.push(res.attachment);
      if (res.downloadUrl && !uploadedUrls.includes(res.downloadUrl)) {
        uploadedUrls.push(res.downloadUrl);
      }
      if (res.storagePath) uploadedPaths.push(res.storagePath);
    }

    for (const file of otherFiles) {
      const att = await uploadFileForNote(
        storage,
        currentUser.uid,
        noteId,
        file,
        undefined,
        firebaseApp
      );
      uploadedAttachments.push(att);
    }

    const noteCategory = draft.category?.trim() || 'General';
    if (noteCategory.toLowerCase() !== 'general') {
      await handleEnsureCategory(noteCategory);
    }

    const newNote: NoteDocument = {
      noteId,
      title: draft.title || 'Untitled Note',
      content: draft.content || '',
      timestamp: now,
      category: noteCategory,
      colorIndex: draft.colorIndex ?? 0,
      isPinned: draft.isPinned ?? false,
      attachments: uploadedAttachments,
      isTaskList: draft.isTaskList ?? false,
      webUrl: draft.webUrl ?? null,
      isScheduled: false,
      scheduledTime: null,
      repeatFrequency: null,
      repeatIntervalMinutes: null,
      repeatUntilTime: null,
      position: 0,
      tags:
        activeSection.type === 'tag'
          ? activeSection.value
          : draft.tags ?? null,
      autoTags: draft.autoTags ?? null,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      imageUrl: uploadedUrls.length > 0 ? uploadedUrls.join('|') : null,
      imageStoragePath: uploadedPaths.length > 0 ? uploadedPaths.join('|') : null,
      isPersistentNotification: false,
      isArchived: false,
    };

    setSyncStatus('SYNCING');
    await saveNoteToFirestore(db, currentUser.uid, newNote);
    setSyncStatus('SYNCED');
    notify(
      usedInlineFallback && imageFiles.length > 0
        ? 'Image note saved & synced with Firestore'
        : 'Note saved and synced'
    );
  };

  const handleSaveNote = async (updated: NoteDocument) => {
    if (!db || !currentUser) return;
    setSyncStatus('SYNCING');
    const normalizedCategory = updated.category?.trim() || 'General';
    if (normalizedCategory.toLowerCase() !== 'general') {
      await handleEnsureCategory(normalizedCategory);
    }
    await saveNoteToFirestore(db, currentUser.uid, {
      ...updated,
      category: normalizedCategory,
      updatedAt: Date.now(),
    });
    setSyncStatus('SYNCED');
    notify('Note updated');
  };

  const handleTogglePin = async (note: NoteDocument) => {
    await handleSaveNote({ ...note, isPinned: !note.isPinned });
  };

  const handleChangeColor = async (note: NoteDocument, colorIndex: number) => {
    await handleSaveNote({ ...note, colorIndex });
  };

  const handleToggleArchive = async (note: NoteDocument) => {
    const nextArchived = !note.isArchived;
    await handleSaveNote({
      ...note,
      isArchived: nextArchived,
      isPinned: nextArchived ? false : note.isPinned,
    });
    notify(nextArchived ? 'Note archived' : 'Note unarchived');
  };

  const handleSoftDelete = async (note: NoteDocument) => {
    await handleSaveNote({
      ...note,
      deletedAt: Date.now(),
      isPinned: false,
    });
    notify('Moved to Trash');
  };

  const handleRestore = async (note: NoteDocument) => {
    await handleSaveNote({
      ...note,
      deletedAt: null,
    });
    notify('Note restored');
  };

  const handlePermanentDelete = async (note: NoteDocument) => {
    if (!db || !storage || !currentUser) return;
    await permanentlyDeleteNote(db, storage, currentUser.uid, note);
    notify('Note permanently deleted');
  };

  const handleEmptyTrash = async () => {
    if (!db || !storage || !currentUser) return;
    const trashed = notes.filter((n) => n.deletedAt !== null);
    for (const note of trashed) {
      await permanentlyDeleteNote(db, storage, currentUser.uid, note);
    }
    notify('Trash emptied');
  };

  const handleAddCategory = async (name: string) => {
    const clean = name.trim();
    if (!db || !currentUser || !clean) return;
    if (categories.some((c) => c.toLowerCase() === clean.toLowerCase())) return;
    const next = [...categories, clean];
    setPrefCategories(next);
    await savePreferencesToFirestore(db, currentUser.uid, next, prefTags);
    notify(`Category "${clean}" synced`);
  };

  const handleDeleteCategory = async (name: string) => {
    if (!db || !currentUser) return;
    const lower = name.toLowerCase();
    const next = categories.filter((c) => c.toLowerCase() !== lower);
    setPrefCategories(next);
    if (activeSection.type === 'category' && activeSection.value.toLowerCase() === lower) {
      setActiveSection({ type: 'notes' });
    }
    await savePreferencesToFirestore(db, currentUser.uid, next, prefTags);

    // Move notes in deleted category back to 'General' so it stays cleanly deleted
    const affected = notes.filter((n) => n.deletedAt === null && n.category.toLowerCase() === lower);
    for (const n of affected) {
      await saveNoteToFirestore(db, currentUser.uid, {
        ...n,
        category: 'General',
        updatedAt: Date.now(),
      });
    }
  };

  const handleAddTag = async (name: string) => {
    const clean = name.trim().replace(/^#/, '').trim();
    if (!db || !currentUser || !clean) return;
    if (tags.some((t) => t.toLowerCase() === clean.toLowerCase())) return;
    const next = [...tags, clean];
    setPrefTags(next);
    await savePreferencesToFirestore(db, currentUser.uid, prefCategories, next);
    notify(`Tag "#${clean}" synced`);
  };

  const handleDeleteTag = async (name: string) => {
    if (!db || !currentUser) return;
    const lower = name.toLowerCase();
    const next = tags.filter((t) => t.toLowerCase() !== lower);
    setPrefTags(next);
    if (activeSection.type === 'tag' && activeSection.value.toLowerCase() === lower) {
      setActiveSection({ type: 'notes' });
    }
    await savePreferencesToFirestore(db, currentUser.uid, prefCategories, next);

    // Remove the deleted tag from notes that hold it so it stays removed across real-time sync
    for (const n of notes) {
      const mTags = splitNoteTags(n.tags);
      const aTags = splitNoteTags(n.autoTags);
      const hasMatch =
        mTags.some((t) => t.toLowerCase() === lower) ||
        aTags.some((t) => t.toLowerCase() === lower);
      if (hasMatch) {
        const nextManual = mTags.filter((t) => t.toLowerCase() !== lower);
        const nextAuto = aTags.filter((t) => t.toLowerCase() !== lower);
        await saveNoteToFirestore(db, currentUser.uid, {
          ...n,
          tags: nextManual.length > 0 ? nextManual.join('|') : null,
          autoTags: nextAuto.length > 0 ? nextAuto.join('|') : null,
          updatedAt: Date.now(),
        });
      }
    }
  };

  const counts = useMemo(() => {
    const active = notes.filter((n) => n.deletedAt === null && !n.isArchived);
    const catCounts: Record<string, number> = {};
    const tCounts: Record<string, number> = {};

    for (const n of active) {
      const cKey = (n.category || 'General').trim().toLowerCase();
      catCounts[cKey] = (catCounts[cKey] || 0) + 1;

      const noteTagSet = new Set([
        ...splitNoteTags(n.tags).map((t) => t.toLowerCase()),
        ...splitNoteTags(n.autoTags).map((t) => t.toLowerCase()),
      ]);
      noteTagSet.forEach((tk) => {
        tCounts[tk] = (tCounts[tk] || 0) + 1;
      });
    }

    return {
      notes: active.length,
      scheduled: active.filter((n) => n.isScheduled && n.scheduledTime !== null).length,
      archive: notes.filter((n) => n.deletedAt === null && n.isArchived).length,
      trash: notes.filter((n) => n.deletedAt !== null).length,
      categories: catCounts,
      tags: tCounts,
    };
  }, [notes]);

  const filteredNotes = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();

    return notes.filter((note) => {
      if (activeSection.type === 'trash') {
        if (note.deletedAt === null) return false;
      } else if (activeSection.type === 'archive') {
        if (note.deletedAt !== null || !note.isArchived) return false;
      } else {
        if (note.deletedAt !== null || note.isArchived) return false;
        if (activeSection.type === 'scheduled') {
          if (!note.isScheduled || note.scheduledTime === null) return false;
        } else if (activeSection.type === 'category') {
          if (note.category.trim().toLowerCase() !== activeSection.value.trim().toLowerCase()) {
            return false;
          }
        } else if (activeSection.type === 'tag') {
          const targetTag = activeSection.value.trim().replace(/^#/, '').toLowerCase();
          const allTags = [
            ...splitNoteTags(note.tags),
            ...splitNoteTags(note.autoTags),
          ].map((t) => t.toLowerCase());
          if (!allTags.includes(targetTag)) return false;
        }
      }

      if (!q) return true;
      const haystack = [
        note.title,
        stripRichTagsToPlainText(note.content),
        note.category,
        note.tags || '',
        note.autoTags || '',
        note.webUrl || '',
      ]
        .join(' ')
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [notes, activeSection, searchQuery]);

  const pinnedNotes = useMemo(
    () =>
      activeSection.type !== 'trash' && activeSection.type !== 'archive'
        ? filteredNotes.filter((n) => n.isPinned)
        : [],
    [filteredNotes, activeSection]
  );

  const otherNotes = useMemo(
    () =>
      activeSection.type !== 'trash' && activeSection.type !== 'archive'
        ? filteredNotes.filter((n) => !n.isPinned)
        : filteredNotes,
    [filteredNotes, activeSection]
  );

  if (booting) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-3">
        <Loader2 className="w-7 h-7 animate-spin" style={{ color: 'var(--accent)' }} />
        <p className="text-sm opacity-70">Connecting to your PINotes cloud workspace...</p>
      </div>
    );
  }

  if (!firebaseConfig || !currentUser) {
    return (
      <SetupAuthScreen
        initialConfig={firebaseConfig}
        onConnect={handleConnect}
        isDark={isDark}
        onToggleTheme={() => setIsDark((v) => !v)}
      />
    );
  }

  // Gate behind Web Passcode Lock before rendering notes (never flashes notes on refresh)
  if (webLock.enabled && !isWebUnlocked) {
    return (
      <PasscodeLockScreen
        userEmail={currentUser.email}
        onVerify={(passcode) =>
          verifyWebPasscode(currentUser.uid, passcode, webLock.passcodeHash)
        }
        onUnlockSuccess={() => setIsWebUnlocked(true)}
        onSignOut={handleSignOut}
      />
    );
  }

  if (webLockLoading && !isWebUnlocked) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-3">
        <Loader2 className="w-7 h-7 animate-spin" style={{ color: 'var(--accent)' }} />
        <p className="text-sm opacity-70">Checking web app lock status...</p>
      </div>
    );
  }

  const sectionTitle = () => {
    switch (activeSection.type) {
      case 'notes':
        return 'All Notes';
      case 'scheduled':
        return 'Scheduled Reminders';
      case 'archive':
        return 'Archived Notes';
      case 'trash':
        return 'Trash';
      case 'category':
        return `Category: ${activeSection.value}`;
      case 'tag':
        return `#${activeSection.value}`;
    }
  };

  const gridClass = isGridView
    ? 'columns-1 sm:columns-2 lg:columns-3 xl:columns-4 gap-4 space-y-4'
    : 'max-w-2xl mx-auto flex flex-col gap-3.5';

  const cardWrapperClass = isGridView ? 'break-inside-avoid' : '';

  return (
    <div className="min-h-screen flex flex-col">
      <TopBar
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        isGridView={isGridView}
        onToggleGridView={() => setIsGridView((v) => !v)}
        isDark={isDark}
        onToggleTheme={() => setIsDark((v) => !v)}
        syncStatus={syncStatus}
        onManualSync={handleManualSync}
        onToggleSidebar={() => setSidebarOpen((v) => !v)}
        onOpenSettings={() => setSettingsOpen(true)}
        userEmail={currentUser.email}
        showLockButton={webLock.enabled}
        onLockNow={handleLockNow}
      />

      <div className="flex flex-1 max-w-[1600px] w-full mx-auto">
        <Sidebar
          isOpen={sidebarOpen}
          onCloseMobile={() => setSidebarOpen(false)}
          activeSection={activeSection}
          onSelectSection={setActiveSection}
          categories={categories}
          tags={tags}
          onAddCategory={handleAddCategory}
          onDeleteCategory={handleDeleteCategory}
          onAddTag={handleAddTag}
          onDeleteTag={handleDeleteTag}
          counts={counts}
        />

        <main className="flex-1 min-w-0 p-4 sm:p-6 lg:p-8">
          {/* Quick Capture Bar (hidden in Trash & Archive) */}
          {activeSection.type !== 'trash' && activeSection.type !== 'archive' && (
            <QuickCaptureBar
              isDark={isDark}
              categories={categories}
              defaultCategory={
                activeSection.type === 'category' ? activeSection.value : 'General'
              }
              onCreateNote={handleCreateNote}
              onEnsureCategory={handleEnsureCategory}
              onOpenSettings={() => setSettingsOpen(true)}
              onNotify={notify}
            />
          )}

          {/* Active Section Header */}
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xs font-bold uppercase tracking-wider opacity-60">
              {sectionTitle()} ({filteredNotes.length})
            </h2>

            {activeSection.type === 'trash' && filteredNotes.length > 0 && (
              <button
                type="button"
                onClick={handleEmptyTrash}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold text-rose-400 hover:bg-rose-500/15 cursor-pointer"
              >
                <Trash2 className="w-3.5 h-3.5" />
                Empty Trash
              </button>
            )}
          </div>

          {/* Empty State */}
          {filteredNotes.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-center opacity-60 space-y-3">
              <div
                className="w-16 h-16 rounded-3xl flex items-center justify-center"
                style={{
                  background: 'var(--accent-container)',
                  color: 'var(--accent-container-text)',
                }}
              >
                <StickyNote className="w-8 h-8" />
              </div>
              <div className="text-base font-semibold">
                {searchQuery ? 'No matching notes found' : 'Nothing here yet'}
              </div>
              <p className="text-xs max-w-xs leading-relaxed">
                {searchQuery
                  ? 'Try searching for another keyword, tag, or category.'
                  : 'Notes you create here or sync from your PINotes Android app will appear automatically.'}
              </p>
            </div>
          ) : (
            <div className="space-y-8">
              {/* Pinned Notes */}
              {pinnedNotes.length > 0 && (
                <section className="space-y-3">
                  <h3 className="text-[11px] font-bold uppercase tracking-widest opacity-55">
                    Pinned
                  </h3>
                  <div className={gridClass}>
                    {pinnedNotes.map((note) => (
                      <div key={note.noteId} className={cardWrapperClass}>
                        <NoteCard
                          note={note}
                          isDark={isDark}
                          storage={storage}
                          firebaseApp={firebaseApp}
                          onOpen={() => setEditingNote(note)}
                          onAttachmentClick={(target) => setPreviewTarget(target)}
                          onTogglePin={() => handleTogglePin(note)}
                          onChangeColor={(c) => handleChangeColor(note, c)}
                          onToggleArchive={() => handleToggleArchive(note)}
                          onSoftDelete={() => handleSoftDelete(note)}
                          onRestore={() => handleRestore(note)}
                          onPermanentDelete={() => handlePermanentDelete(note)}
                          onUpdateContent={(newContent) =>
                            handleSaveNote({ ...note, content: newContent })
                          }
                          onNotify={notify}
                        />
                      </div>
                    ))}
                  </div>
                </section>
              )}

              {/* Other Notes */}
              {otherNotes.length > 0 && (
                <section className="space-y-3">
                  {pinnedNotes.length > 0 && (
                    <h3 className="text-[11px] font-bold uppercase tracking-widest opacity-55">
                      Others
                    </h3>
                  )}
                  <div className={gridClass}>
                    {otherNotes.map((note) => (
                      <div key={note.noteId} className={cardWrapperClass}>
                        <NoteCard
                          note={note}
                          isDark={isDark}
                          storage={storage}
                          firebaseApp={firebaseApp}
                          onOpen={() => setEditingNote(note)}
                          onAttachmentClick={(target) => setPreviewTarget(target)}
                          onTogglePin={() => handleTogglePin(note)}
                          onChangeColor={(c) => handleChangeColor(note, c)}
                          onToggleArchive={() => handleToggleArchive(note)}
                          onSoftDelete={() => handleSoftDelete(note)}
                          onRestore={() => handleRestore(note)}
                          onPermanentDelete={() => handlePermanentDelete(note)}
                          onUpdateContent={(newContent) =>
                            handleSaveNote({ ...note, content: newContent })
                          }
                          onNotify={notify}
                        />
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </div>
          )}
        </main>
      </div>

      {/* Note Editor Modal */}
      {editingNote && (
        <NoteEditorModal
          note={editingNote}
          isDark={isDark}
          storage={storage}
          firebaseApp={firebaseApp}
          categories={categories}
          tags={tags}
          onClose={() => setEditingNote(null)}
          onSave={handleSaveNote}
          onEnsureCategory={handleEnsureCategory}
          onUploadImage={(noteId, file) =>
            uploadImageForNote(
              storage!,
              currentUser.uid,
              noteId,
              file,
              undefined,
              firebaseApp
            )
          }
          onUploadFile={(noteId, file) =>
            uploadFileForNote(
              storage!,
              currentUser.uid,
              noteId,
              file,
              undefined,
              firebaseApp
            )
          }
          onDeleteStoragePath={(path) => deleteStoragePathSafe(storage!, path)}
          onToggleArchive={handleToggleArchive}
          onSoftDelete={handleSoftDelete}
          onAttachmentClick={(target) => setPreviewTarget(target)}
          onOpenSettings={() => setSettingsOpen(true)}
          onNotify={notify}
        />
      )}

      {/* Rich Attachment & Image Lightbox Modal */}
      <AttachmentPreviewModal
        target={previewTarget}
        storage={storage}
        firebaseApp={firebaseApp}
        onClose={() => setPreviewTarget(null)}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        config={firebaseConfig}
        userEmail={currentUser.email}
        userUid={currentUser.uid}
        webLock={webLock}
        onSetWebPasscode={handleSetWebPasscode}
        onResetWebPasscode={handleResetWebPasscode}
        onUnsetWebPasscode={handleUnsetWebPasscode}
        onLockNow={handleLockNow}
        onManualSync={handleManualSync}
        onSignOut={handleSignOut}
        onForgetConfigAndSignOut={handleForgetConfigAndSignOut}
        onNotify={notify}
      />

      {/* Toast Notification */}
      {toastMsg && (
        <div
          className="fixed bottom-5 left-1/2 -translate-x-1/2 z-[80] px-4 py-2.5 rounded-2xl text-xs font-semibold shadow-2xl border"
          style={{
            background: 'var(--bg-surface-elevated)',
            color: 'var(--text-primary)',
            borderColor: 'var(--border-subtle)',
          }}
        >
          {toastMsg}
        </div>
      )}
    </div>
  );
}

export default App;
