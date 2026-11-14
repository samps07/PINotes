import React from 'react';
import {
  Menu,
  Search,
  X,
  LayoutGrid,
  List,
  RefreshCw,
  Sun,
  Moon,
  Settings,
  CheckCircle2,
  WifiOff,
  AlertTriangle,
} from 'lucide-react';
import { SyncStatus } from '../types/note';

interface TopBarProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  isGridView: boolean;
  onToggleGridView: () => void;
  isDark: boolean;
  onToggleTheme: () => void;
  syncStatus: SyncStatus;
  onManualSync: () => void;
  onToggleSidebar: () => void;
  onOpenSettings: () => void;
  userEmail: string | null;
}

export const TopBar: React.FC<TopBarProps> = ({
  searchQuery,
  onSearchChange,
  isGridView,
  onToggleGridView,
  isDark,
  onToggleTheme,
  syncStatus,
  onManualSync,
  onToggleSidebar,
  onOpenSettings,
  userEmail,
}) => {
  const statusChip = () => {
    switch (syncStatus) {
      case 'SYNCING':
        return {
          icon: <RefreshCw className="w-3.5 h-3.5 animate-spin" />,
          label: 'Syncing',
          cls: 'text-amber-300 bg-amber-500/15 border-amber-500/30',
        };
      case 'OFFLINE':
        return {
          icon: <WifiOff className="w-3.5 h-3.5" />,
          label: 'Offline',
          cls: 'text-slate-300 bg-slate-500/15 border-slate-500/30',
        };
      case 'ERROR':
        return {
          icon: <AlertTriangle className="w-3.5 h-3.5" />,
          label: 'Sync Error',
          cls: 'text-rose-300 bg-rose-500/15 border-rose-500/30',
        };
      default:
        return {
          icon: <CheckCircle2 className="w-3.5 h-3.5" />,
          label: 'Synced',
          cls: 'text-emerald-400 bg-emerald-500/15 border-emerald-500/30',
        };
    }
  };

  const chip = statusChip();

  return (
    <header
      className="sticky top-0 z-30 border-b px-3 sm:px-5 py-2.5 backdrop-blur-md"
      style={{
        background: isDark ? 'rgba(28, 27, 31, 0.9)' : 'rgba(246, 244, 249, 0.9)',
        borderColor: 'var(--border-subtle)',
      }}
    >
      <div className="flex items-center gap-2 sm:gap-4 max-w-[1600px] mx-auto">
        {/* Left: Menu + Brand */}
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={onToggleSidebar}
            title="Toggle sidebar"
            className="p-2.5 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
          >
            <Menu className="w-5 h-5" />
          </button>

          <div className="flex items-center gap-2">
            <div
              className="w-8 h-8 rounded-xl flex items-center justify-center"
              style={{
                background: 'var(--accent-container)',
                color: 'var(--accent-container-text)',
              }}
            >
              <svg viewBox="0 0 24 24" className="w-4 h-4 fill-current">
                <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5v6l1 1 1-1v-6h5v-2l-2-2z" />
              </svg>
            </div>
            <span className="font-display font-bold text-lg hidden sm:inline tracking-tight">
              PINotes
            </span>
          </div>
        </div>

        {/* Center: Search Bar */}
        <div className="flex-1 max-w-2xl mx-auto">
          <div
            className="flex items-center gap-2.5 rounded-2xl px-3.5 py-2 border transition-all focus-within:ring-2 focus-within:ring-[#D0BCFF]/40"
            style={{
              background: 'var(--bg-surface)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            <Search className="w-4 h-4 opacity-55 shrink-0" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="Search notes, checklists, tables, tags, or links..."
              className="w-full bg-transparent text-sm focus:outline-none"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => onSearchChange('')}
                className="p-1 rounded-full hover:bg-white/10 cursor-pointer"
                title="Clear search"
              >
                <X className="w-3.5 h-3.5 opacity-70" />
              </button>
            )}
          </div>
        </div>

        {/* Right: Actions */}
        <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
          <button
            type="button"
            onClick={onManualSync}
            title="Sync now"
            className={`hidden md:inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-full text-xs font-medium border cursor-pointer transition-opacity hover:opacity-85 ${chip.cls}`}
          >
            {chip.icon}
            <span>{chip.label}</span>
          </button>

          <button
            type="button"
            onClick={onToggleGridView}
            title={isGridView ? 'Switch to List view' : 'Switch to Grid view'}
            className="p-2.5 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
          >
            {isGridView ? <List className="w-5 h-5" /> : <LayoutGrid className="w-5 h-5" />}
          </button>

          <button
            type="button"
            onClick={onToggleTheme}
            title={isDark ? 'Switch to Light theme' : 'Switch to Dark theme'}
            className="p-2.5 rounded-full hover:bg-white/10 transition-colors cursor-pointer"
          >
            {isDark ? <Sun className="w-5 h-5" /> : <Moon className="w-5 h-5" />}
          </button>

          <button
            type="button"
            onClick={onOpenSettings}
            title={userEmail ? `Settings (${userEmail})` : 'Sync & Account Settings'}
            className="flex items-center gap-2 pl-2 pr-2.5 py-1.5 rounded-full border hover:opacity-90 transition-opacity cursor-pointer"
            style={{
              background: 'var(--bg-surface)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            <div
              className="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold"
              style={{
                background: 'var(--accent)',
                color: 'var(--accent-on)',
              }}
            >
              {(userEmail?.[0] || 'P').toUpperCase()}
            </div>
            <Settings className="w-4 h-4 opacity-70 hidden sm:block" />
          </button>
        </div>
      </div>
    </header>
  );
};
