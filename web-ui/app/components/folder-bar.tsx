// ==================== [F7] 文件夹栏（自 conversation-sidebar.tsx 拆出） ====================

import * as React from "react";

import { Folder, FolderPlus, MoreHorizontal, Pencil, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { cn } from "~/lib/utils";
import type { FolderDto } from "~/types";

export interface FolderBarProps {
  folders: FolderDto[];
  selectedFolderId: string | null;
  onSelect: (folderId: string | null) => void;
  onCreate?: (name: string) => Promise<void>;
  onRename?: (id: string, name: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
}

export function FolderBar({
  folders,
  selectedFolderId,
  onSelect,
  onCreate,
  onRename,
  onDelete,
}: FolderBarProps) {
  const { t } = useTranslation();

  const handleCreate = React.useCallback(() => {
    if (!onCreate) return;
    const name = window.prompt(t("conversation_sidebar.folder_name_prompt"))?.trim();
    if (!name) return;
    void onCreate(name).catch((folderError) => {
      console.error("Create folder failed", folderError);
      toast.error(t("conversation_sidebar.folder_create_failed"));
    });
  }, [onCreate, t]);

  const handleRename = React.useCallback(
    (folder: FolderDto) => {
      if (!onRename) return;
      const name = window
        .prompt(t("conversation_sidebar.folder_rename_prompt"), folder.name)
        ?.trim();
      if (!name || name === folder.name) return;
      void onRename(folder.id, name).catch((folderError) => {
        console.error("Rename folder failed", folderError);
        toast.error(t("conversation_sidebar.folder_rename_failed"));
      });
    },
    [onRename, t],
  );

  const handleDelete = React.useCallback(
    (folder: FolderDto) => {
      if (!onDelete) return;
      if (
        !window.confirm(t("conversation_sidebar.folder_delete_confirm", { folder: folder.name }))
      ) {
        return;
      }
      void onDelete(folder.id).catch((folderError) => {
        console.error("Delete folder failed", folderError);
        toast.error(t("conversation_sidebar.folder_delete_failed"));
      });
    },
    [onDelete, t],
  );

  return (
    <div className="flex items-center gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <button
        type="button"
        onClick={() => onSelect(null)}
        className={cn(
          "inline-flex shrink-0 items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition",
          selectedFolderId === null
            ? "bg-primary text-primary-foreground"
            : "bg-muted text-muted-foreground hover:bg-muted/80",
        )}
      >
        {t("conversation_sidebar.folder_all")}
      </button>

      {folders.map((folder) => {
        const selected = folder.id === selectedFolderId;
        return (
          <DropdownMenu key={folder.id}>
            <div
              role="button"
              tabIndex={0}
              onClick={() => onSelect(folder.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelect(folder.id);
                }
              }}
              onContextMenu={(event) => {
                if (!onRename && !onDelete) return;
                event.preventDefault();
              }}
              className={cn(
                "inline-flex shrink-0 cursor-pointer items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition",
                selected
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground hover:bg-muted/80",
              )}
            >
              <Folder className="size-3" />
              <span className="max-w-[8rem] truncate">{folder.name}</span>
              {(onRename || onDelete) && (
                <DropdownMenuTrigger asChild>
                  <span
                    role="button"
                    tabIndex={-1}
                    aria-label={t("conversation_sidebar.folder_actions")}
                    onClick={(event) => event.stopPropagation()}
                    className="ml-0.5 inline-flex items-center opacity-70 hover:opacity-100"
                  >
                    <MoreHorizontal className="size-3" />
                  </span>
                </DropdownMenuTrigger>
              )}
            </div>
            <DropdownMenuContent side="bottom" align="start" className="w-40">
              {onRename && (
                <DropdownMenuItem onSelect={() => handleRename(folder)}>
                  <Pencil className="size-4" />
                  <span>{t("conversation_sidebar.folder_rename")}</span>
                </DropdownMenuItem>
              )}
              {onDelete && (
                <DropdownMenuItem variant="destructive" onSelect={() => handleDelete(folder)}>
                  <Trash2 className="size-4" />
                  <span>{t("conversation_sidebar.folder_delete")}</span>
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        );
      })}

      {onCreate && (
        <button
          type="button"
          onClick={handleCreate}
          aria-label={t("conversation_sidebar.folder_create")}
          title={t("conversation_sidebar.folder_create")}
          className="inline-flex shrink-0 items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground transition hover:bg-muted/80"
        >
          <FolderPlus className="size-3.5" />
        </button>
      )}
    </div>
  );
}

