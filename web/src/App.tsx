import { useEffect, useMemo, useState, useCallback } from 'react';
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
  saveNoteToFirestore,
  savePreferencesToFirestore,
  subscribeToNotes,
  subscribeToPreferences,
  uploadFileForNote,
  uploadImageForNote,
  deleteStoragePathSafe,
} from './services/notesService';
import { stripRichTagsToPlainText } from './utils/richText';
import { SetupAuthScreen } from './components/SetupAuthScreen';
import { TopBar } from './components/TopBar';
import { Sidebar } from './components/Sidebar';
import { QuickCaptureBar } from './components/QuickCaptureBar';
import { NoteCard } from './components/NoteCard';
import { NoteEditorModal } from './components/NoteEditorModal';
import { LightboxModal } from './components/LightboxModal';
import { SettingsModal } from './components/SettingsModal';

const THEME_KEY = 'pinotes_theme_v1';
const VIEW_KEY = 'pinotes_grid_view_v1';

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
  const [db, setDb] = useState<Firestore | null>(null);
  const [storage, setStorage] = useState<FirebaseStorage | null>(null);
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [booting, setBooting] = useState<boolean>(() => Boolean(loadSavedFirebaseConfig()));

  const [notes, setNotes] = useState<NoteDocument[]>([]);
  const [categories, setCategories] = useState<string[]>(DEFAULT_CATEGORIES);
  const [tags, setTags] = useState<string[]>(DEFAULT_TAGS);
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('OFFLINE');

  const [sidebarOpen, setSidebarOpen] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.innerWidth >= 1024 : true
  );
  const [activeSection, setActiveSection] = useState<SidebarSection>({ type: 'notes' });
  const [searchQuery, setSearchQuery] = useState('');

  const [editingNote, setEditingNote] = useState<NoteDocument | null>(null);
  const [lightboxUrl, setLightboxUrl] = useState<string | null>(null);
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
        setDb(instances.db);
        setStorage(instances.storage);

        unsubAuth = onAuthStateChanged(instances.auth, (user) => {
          if (cancelled) return;
          setCurrentUser(user);
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

  // Subscribe to Firestore notes & preferences when authenticated
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

    const unsubPrefs = subscribeToPreferences(db, currentUser.uid, (cats, tgs) => {
      setCategories(cats);
      setTags(tgs);
    });

    return () => {
      unsubNotes();
      unsubPrefs();
    };
  }, [db, currentUser, notify]);

  const handleConnect = async (
    config: FirebaseUserConfig,
    email: string,
    password: string,
    mode: 'signin' | 'signup'
  ) => {
    const saved = saveFirebaseConfig(config);
    const instances = await getOrInitFirebaseInstances(saved);

    if (mode === 'signin') {
      await signInWithEmailAndPassword(instances.auth, email, password);
    } else {
      await createUserWithEmailAndPassword(instances.auth, email, password);
    }

    setDb(instances.db);
    setStorage(instances.storage);
    setFirebaseConfig(saved);
  };

  const handleManualSync = async () => {
    if (!db || !currentUser) return;
    setSyncStatus('SYNCING');
    try {
      const fresh = await refreshNotesOnce(db, currentUser.uid);
      fresh.sort((a, b) => {
        if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1;
        return (b.updatedAt || b.timestamp) - (a.updatedAt || a.timestamp);
      });
      setNotes(fresh);
      setSyncStatus('SYNCED');
      notify('Notes synced from cloud');
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
    setCurrentUser(null);
    setNotes([]);
    setSyncStatus('OFFLINE');
  };

  const handleForgetConfigAndSignOut = async () => {
    await handleSignOut();
    await teardownFirebaseInstance();
    clearSavedFirebaseConfig();
    setFirebaseConfig(null);
    setDb(null);
    setStorage(null);
  };

  const handleCreateNote = async (
    draft: Partial<NoteDocument>,
    filesToUpload: File[] = []
  ) => {
    if (!db || !storage || !currentUser) return;
    const now = Date.now();
    const noteId = crypto.randomUUID ? crypto.randomUUID() : `web_${now}_${Math.random()}`;

    const uploadedAttachments = [];
    const uploadedUrls: string[] = [];
    const uploadedPaths: string[] = [];

    for (const file of filesToUpload) {
      const res = await uploadImageForNote(storage, currentUser.uid, noteId, file);
      uploadedAttachments.push(res.attachment);
      uploadedUrls.push(res.downloadUrl);
      uploadedPaths.push(res.storagePath);
    }

    const newNote: NoteDocument = {
      noteId,
      title: draft.title || 'Untitled Note',
      content: draft.content || '',
      timestamp: now,
      category: draft.category || 'General',
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
      autoTags: null,
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
    notify('Note saved and synced');
  };

  const handleSaveNote = async (updated: NoteDocument) => {
    if (!db || !currentUser) return;
    setSyncStatus('SYNCING');
    await saveNoteToFirestore(db, currentUser.uid, {
      ...updated,
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
    if (!db || !currentUser || categories.includes(name)) return;
    const next = [...categories, name];
    setCategories(next);
    await savePreferencesToFirestore(db, currentUser.uid, next, tags);
    notify(`Category "${name}" added`);
  };

  const handleDeleteCategory = async (name: string) => {
    if (!db || !currentUser) return;
    const next = categories.filter((c) => c !== name);
    setCategories(next);
    if (activeSection.type === 'category' && activeSection.value === name) {
      setActiveSection({ type: 'notes' });
    }
    await savePreferencesToFirestore(db, currentUser.uid, next, tags);
  };

  const handleAddTag = async (name: string) => {
    if (!db || !currentUser || tags.includes(name)) return;
    const next = [...tags, name];
    setTags(next);
    await savePreferencesToFirestore(db, currentUser.uid, categories, next);
    notify(`Tag "#${name}" added`);
  };

  const handleDeleteTag = async (name: string) => {
    if (!db || !currentUser) return;
    const next = tags.filter((t) => t !== name);
    setTags(next);
    if (activeSection.type === 'tag' && activeSection.value === name) {
      setActiveSection({ type: 'notes' });
    }
    await savePreferencesToFirestore(db, currentUser.uid, categories, next);
  };

  const counts = useMemo(() => {
    const active = notes.filter((n) => n.deletedAt === null && !n.isArchived);
    return {
      notes: active.length,
      scheduled: active.filter((n) => n.isScheduled && n.scheduledTime !== null).length,
      archive: notes.filter((n) => n.deletedAt === null && n.isArchived).length,
      trash: notes.filter((n) => n.deletedAt !== null).length,
    };
  }, [notes]);

  const filteredNotes = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();

    return notes.filter((note) => {
      // Section filter
      if (activeSection.type === 'trash') {
        if (note.deletedAt === null) return false;
      } else if (activeSection.type === 'archive') {
        if (note.deletedAt !== null || !note.isArchived) return false;
      } else {
        if (note.deletedAt !== null || note.isArchived) return false;
        if (activeSection.type === 'scheduled') {
          if (!note.isScheduled || note.scheduledTime === null) return false;
        } else if (activeSection.type === 'category') {
          if (note.category !== activeSection.value) return false;
        } else if (activeSection.type === 'tag') {
          const allTags = [
            ...(note.tags ? note.tags.split('|') : []),
            ...(note.autoTags ? note.autoTags.split('|') : []),
          ].map((t) => t.trim());
          if (!allTags.includes(activeSection.value)) return false;
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
                          onOpen={() => setEditingNote(note)}
                          onImageClick={(url) => setLightboxUrl(url)}
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
                          onOpen={() => setEditingNote(note)}
                          onImageClick={(url) => setLightboxUrl(url)}
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
          categories={categories}
          tags={tags}
          onClose={() => setEditingNote(null)}
          onSave={handleSaveNote}
          onUploadImage={(noteId, file) =>
            uploadImageForNote(storage!, currentUser.uid, noteId, file)
          }
          onUploadFile={(noteId, file) =>
            uploadFileForNote(storage!, currentUser.uid, noteId, file)
          }
          onDeleteStoragePath={(path) => deleteStoragePathSafe(storage!, path)}
          onToggleArchive={handleToggleArchive}
          onSoftDelete={handleSoftDelete}
          onImageClick={(url) => setLightboxUrl(url)}
          onNotify={notify}
        />
      )}

      {/* Lightbox */}
      <LightboxModal imageUrl={lightboxUrl} onClose={() => setLightboxUrl(null)} />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        config={firebaseConfig}
        userEmail={currentUser.email}
        userUid={currentUser.uid}
        onManualSync={handleManualSync}
        onSignOut={handleSignOut}
        onForgetConfigAndSignOut={handleForgetConfigAndSignOut}
      />

      {/* Toast Notification */}
      {toastMsg && (
        <div
          className="fixed bottom-5 left-1/2 -translate-x-1/2 z-[70] px-4 py-2.5 rounded-2xl text-xs font-semibold shadow-2xl border"
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
