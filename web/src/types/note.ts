export interface SyncAttachment {
  fileName: string;
  mimeType: string;
  fileSize: number;
  storagePath: string;
  downloadUrl: string;
}

export interface NoteDocument {
  noteId: string;
  title: string;
  content: string;
  timestamp: number;
  category: string;
  colorIndex: number;
  isPinned: boolean;
  attachments: SyncAttachment[];
  isTaskList: boolean;
  webUrl: string | null;
  isScheduled: boolean;
  scheduledTime: number | null;
  repeatFrequency: string | null;
  repeatIntervalMinutes: number | null;
  repeatUntilTime: number | null;
  position: number;
  tags: string | null;
  autoTags: string | null;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
  imageUrl: string | null;
  imageStoragePath: string | null;
  isPersistentNotification: boolean;
  isArchived: boolean;
}

export interface TableTaskItem {
  values: string[];
  isChecked: boolean;
}

export interface TableListData {
  headers: string[];
  items: TableTaskItem[];
}

export interface FirebaseUserConfig {
  apiKey: string;
  appId: string;
  projectId: string;
  storageBucket: string;
}

export interface WebPasscodeConfig {
  enabled: boolean;
  passcodeHash: string | null;
  updatedAt: number;
}

export type SyncStatus = 'SYNCED' | 'SYNCING' | 'OFFLINE' | 'ERROR';

export type SidebarSection =
  | { type: 'notes' }
  | { type: 'scheduled' }
  | { type: 'archive' }
  | { type: 'trash' }
  | { type: 'category'; value: string }
  | { type: 'tag'; value: string };

export interface NoteColorSwatch {
  index: number;
  name: string;
  darkBg: string;
  darkText: string;
  lightBg: string;
  lightText: string;
}

export const NOTE_COLORS: NoteColorSwatch[] = [
  {
    index: 0,
    name: 'Default',
    darkBg: '#2B2930',
    darkText: '#E6E1E5',
    lightBg: '#FFFFFF',
    lightText: '#1C1B1F',
  },
  {
    index: 1,
    name: 'Burgundy',
    darkBg: '#3D2C2E',
    darkText: '#FFD9DF',
    lightBg: '#FCE8EB',
    lightText: '#4A1C24',
  },
  {
    index: 2,
    name: 'Terracotta',
    darkBg: '#3C312B',
    darkText: '#FFDEC7',
    lightBg: '#FDECE2',
    lightText: '#4D2B18',
  },
  {
    index: 3,
    name: 'Bronze Gold',
    darkBg: '#3B3929',
    darkText: '#FFF3BE',
    lightBg: '#FAF3D1',
    lightText: '#473E0B',
  },
  {
    index: 4,
    name: 'Emerald',
    darkBg: '#2B3A34',
    darkText: '#CBF1D9',
    lightBg: '#E1F5EA',
    lightText: '#113B26',
  },
  {
    index: 5,
    name: 'Slate Blue',
    darkBg: '#273240',
    darkText: '#CFE4FF',
    lightBg: '#E3EFFC',
    lightText: '#122E4D',
  },
  {
    index: 6,
    name: 'Violet',
    darkBg: '#332D41',
    darkText: '#D0BCFF',
    lightBg: '#EFE7FC',
    lightText: '#381E72',
  },
];

export function getNoteColors(colorIndex: number, isDark: boolean) {
  const swatch = NOTE_COLORS[colorIndex] ?? NOTE_COLORS[0];
  return {
    bg: isDark ? swatch.darkBg : swatch.lightBg,
    text: isDark ? swatch.darkText : swatch.lightText,
    name: swatch.name,
  };
}

export const DEFAULT_CATEGORIES = ['General', 'Personal', 'Work', 'Ideas', 'Reminders'];
export const DEFAULT_TAGS = ['Urgent', 'Reference', 'School', 'Finance', 'Family'];
