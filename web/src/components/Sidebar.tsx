import React, { useState } from 'react';
import {
  StickyNote,
  Bell,
  Archive,
  Trash2,
  Folder,
  Hash,
  Plus,
  X,
} from 'lucide-react';
import { SidebarSection } from '../types/note';

interface SidebarProps {
  isOpen: boolean;
  onCloseMobile: () => void;
  activeSection: SidebarSection;
  onSelectSection: (section: SidebarSection) => void;
  categories: string[];
  tags: string[];
  onAddCategory: (name: string) => void;
  onDeleteCategory: (name: string) => void;
  onAddTag: (name: string) => void;
  onDeleteTag: (name: string) => void;
  counts: {
    notes: number;
    scheduled: number;
    archive: number;
    trash: number;
  };
}

export const Sidebar: React.FC<SidebarProps> = ({
  isOpen,
  onCloseMobile,
  activeSection,
  onSelectSection,
  categories,
  tags,
  onAddCategory,
  onDeleteCategory,
  onAddTag,
  onDeleteTag,
  counts,
}) => {
  const [newCategory, setNewCategory] = useState('');
  const [addingCat, setAddingCat] = useState(false);
  const [newTag, setNewTag] = useState('');
  const [addingTag, setAddingTag] = useState(false);

  const isSelected = (sec: SidebarSection) => {
    if (sec.type !== activeSection.type) return false;
    if (sec.type === 'category' && activeSection.type === 'category') {
      return sec.value === activeSection.value;
    }
    if (sec.type === 'tag' && activeSection.type === 'tag') {
      return sec.value === activeSection.value;
    }
    return true;
  };

  const navBtnStyle = (selected: boolean) =>
    selected
      ? {
          background: 'var(--accent-container)',
          color: 'var(--accent-container-text)',
        }
      : {};

  const handleSelect = (sec: SidebarSection) => {
    onSelectSection(sec);
    if (window.innerWidth < 1024) {
      onCloseMobile();
    }
  };

  const submitCategory = (e: React.FormEvent) => {
    e.preventDefault();
    if (newCategory.trim()) {
      onAddCategory(newCategory.trim());
      setNewCategory('');
      setAddingCat(false);
    }
  };

  const submitTag = (e: React.FormEvent) => {
    e.preventDefault();
    if (newTag.trim()) {
      onAddTag(newTag.trim());
      setNewTag('');
      setAddingTag(false);
    }
  };

  const sidebarContent = (
    <nav className="flex flex-col h-full py-3 pr-2 pl-2 overflow-y-auto space-y-5">
      {/* Primary Sections */}
      <div className="space-y-1">
        <button
          type="button"
          onClick={() => handleSelect({ type: 'notes' })}
          style={navBtnStyle(isSelected({ type: 'notes' }))}
          className="w-full flex items-center justify-between px-4 py-2.5 rounded-r-full text-sm font-medium transition-colors hover:bg-white/5 cursor-pointer"
        >
          <span className="flex items-center gap-3">
            <StickyNote className="w-4 h-4" />
            Notes
          </span>
          <span className="text-xs opacity-65">{counts.notes}</span>
        </button>

        <button
          type="button"
          onClick={() => handleSelect({ type: 'scheduled' })}
          style={navBtnStyle(isSelected({ type: 'scheduled' }))}
          className="w-full flex items-center justify-between px-4 py-2.5 rounded-r-full text-sm font-medium transition-colors hover:bg-white/5 cursor-pointer"
        >
          <span className="flex items-center gap-3">
            <Bell className="w-4 h-4" />
            Scheduled
          </span>
          {counts.scheduled > 0 && (
            <span className="text-xs opacity-65">{counts.scheduled}</span>
          )}
        </button>

        <button
          type="button"
          onClick={() => handleSelect({ type: 'archive' })}
          style={navBtnStyle(isSelected({ type: 'archive' }))}
          className="w-full flex items-center justify-between px-4 py-2.5 rounded-r-full text-sm font-medium transition-colors hover:bg-white/5 cursor-pointer"
        >
          <span className="flex items-center gap-3">
            <Archive className="w-4 h-4" />
            Archive
          </span>
          {counts.archive > 0 && (
            <span className="text-xs opacity-65">{counts.archive}</span>
          )}
        </button>

        <button
          type="button"
          onClick={() => handleSelect({ type: 'trash' })}
          style={navBtnStyle(isSelected({ type: 'trash' }))}
          className="w-full flex items-center justify-between px-4 py-2.5 rounded-r-full text-sm font-medium transition-colors hover:bg-white/5 cursor-pointer"
        >
          <span className="flex items-center gap-3">
            <Trash2 className="w-4 h-4" />
            Trash
          </span>
          {counts.trash > 0 && (
            <span className="text-xs opacity-65">{counts.trash}</span>
          )}
        </button>
      </div>

      {/* Categories */}
      <div className="space-y-1">
        <div className="flex items-center justify-between px-4 pb-1">
          <span className="text-[11px] font-bold uppercase tracking-wider opacity-55">
            Categories
          </span>
          <button
            type="button"
            onClick={() => setAddingCat((v) => !v)}
            className="p-1 rounded-full hover:bg-white/10 cursor-pointer"
            title="Add category"
          >
            <Plus className="w-3.5 h-3.5 opacity-75" />
          </button>
        </div>

        {addingCat && (
          <form onSubmit={submitCategory} className="px-3 pb-2 flex gap-1.5">
            <input
              type="text"
              value={newCategory}
              onChange={(e) => setNewCategory(e.target.value)}
              placeholder="Category name"
              autoFocus
              className="w-full rounded-xl px-2.5 py-1.5 text-xs border focus:outline-none"
              style={{
                background: 'var(--bg-surface)',
                borderColor: 'var(--border-subtle)',
              }}
            />
            <button
              type="submit"
              className="px-2.5 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
              style={{
                background: 'var(--accent)',
                color: 'var(--accent-on)',
              }}
            >
              Add
            </button>
          </form>
        )}

        {categories.map((cat) => {
          const active = isSelected({ type: 'category', value: cat });
          return (
            <div
              key={cat}
              className="group flex items-center rounded-r-full transition-colors hover:bg-white/5"
              style={navBtnStyle(active)}
            >
              <button
                type="button"
                onClick={() => handleSelect({ type: 'category', value: cat })}
                className="flex-1 flex items-center gap-3 px-4 py-2 text-sm font-medium text-left truncate cursor-pointer"
              >
                <Folder className="w-4 h-4 shrink-0" />
                <span className="truncate">{cat}</span>
              </button>
              {cat !== 'General' && (
                <button
                  type="button"
                  onClick={() => onDeleteCategory(cat)}
                  title={`Remove ${cat}`}
                  className="opacity-0 group-hover:opacity-70 hover:!opacity-100 p-1.5 mr-1 rounded-full cursor-pointer"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          );
        })}
      </div>

      {/* Tags */}
      <div className="space-y-1">
        <div className="flex items-center justify-between px-4 pb-1">
          <span className="text-[11px] font-bold uppercase tracking-wider opacity-55">
            Tags
          </span>
          <button
            type="button"
            onClick={() => setAddingTag((v) => !v)}
            className="p-1 rounded-full hover:bg-white/10 cursor-pointer"
            title="Add tag"
          >
            <Plus className="w-3.5 h-3.5 opacity-75" />
          </button>
        </div>

        {addingTag && (
          <form onSubmit={submitTag} className="px-3 pb-2 flex gap-1.5">
            <input
              type="text"
              value={newTag}
              onChange={(e) => setNewTag(e.target.value)}
              placeholder="Tag name"
              autoFocus
              className="w-full rounded-xl px-2.5 py-1.5 text-xs border focus:outline-none"
              style={{
                background: 'var(--bg-surface)',
                borderColor: 'var(--border-subtle)',
              }}
            />
            <button
              type="submit"
              className="px-2.5 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
              style={{
                background: 'var(--accent)',
                color: 'var(--accent-on)',
              }}
            >
              Add
            </button>
          </form>
        )}

        {tags.map((tag) => {
          const active = isSelected({ type: 'tag', value: tag });
          return (
            <div
              key={tag}
              className="group flex items-center rounded-r-full transition-colors hover:bg-white/5"
              style={navBtnStyle(active)}
            >
              <button
                type="button"
                onClick={() => handleSelect({ type: 'tag', value: tag })}
                className="flex-1 flex items-center gap-3 px-4 py-2 text-sm font-medium text-left truncate cursor-pointer"
              >
                <Hash className="w-4 h-4 shrink-0" />
                <span className="truncate">{tag}</span>
              </button>
              <button
                type="button"
                onClick={() => onDeleteTag(tag)}
                title={`Remove ${tag}`}
                className="opacity-0 group-hover:opacity-70 hover:!opacity-100 p-1.5 mr-1 rounded-full cursor-pointer"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          );
        })}
      </div>
    </nav>
  );

  return (
    <>
      {/* Desktop persistent / collapsible sidebar */}
      <aside
        className={`hidden lg:block shrink-0 transition-all duration-200 overflow-hidden ${
          isOpen ? 'w-64' : 'w-0'
        }`}
      >
        <div className="w-64 h-[calc(100vh-58px)] sticky top-[58px]">
          {sidebarContent}
        </div>
      </aside>

      {/* Mobile slide-over drawer */}
      {isOpen && (
        <div className="lg:hidden fixed inset-0 z-40 flex">
          <div
            className="fixed inset-0 bg-black/50 backdrop-blur-xs"
            onClick={onCloseMobile}
          />
          <div
            className="relative w-72 max-w-[82vw] h-full shadow-2xl border-r z-10"
            style={{
              background: 'var(--bg-canvas)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            {sidebarContent}
          </div>
        </div>
      )}
    </>
  );
};
