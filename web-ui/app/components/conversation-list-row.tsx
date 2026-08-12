import * as React from "react";

import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import {
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
} from "~/components/ui/sidebar";
import type {
  AssistantProfile,
  ConversationListDto,
  FolderDto,
} from "~/types";

export interface ConversationListRowProps {
interface ConversationListRowProps {
  conversation: ConversationListDto;
  isActive: boolean;
  assistants: AssistantProfile[];
  folders: FolderDto[];
  onSelect: (id: string) => void;
  onPin?: (id: string) => Promise<void>;
  onRegenerateTitle?: (id: string) => Promise<void>;
  onMoveToAssistant?: (id: string, assistantId: string) => Promise<void>;
  onMoveToFolder?: (conversationId: string, folderId: string | null) => Promise<void>;
  onUpdateTitle?: (id: string, title: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
}

export const ConversationListRow = React.memo(
  ({
    conversation,
    isActive,
    assistants,
    folders,
    onSelect,
    onPin,
    onRegenerateTitle,
    onMoveToAssistant,
    onMoveToFolder,
    onUpdateTitle,
    onDelete,
  }: ConversationListRowProps) => {
    const { t } = useTranslation();
    const [menuOpen, setMenuOpen] = React.useState(false);
    const [pendingAction, setPendingAction] = React.useState<string | null>(null);

    const moveTargets = React.useMemo(
      () => assistants.filter((assistant) => assistant.id !== conversation.assistantId),
      [assistants, conversation.assistantId],
    );

    const hasMenuAction = Boolean(
      onPin ||
      onRegenerateTitle ||
      onMoveToAssistant ||
      onMoveToFolder ||
      onUpdateTitle ||
      onDelete,
    );

    const runAction = React.useCallback(
      async (
        actionId: string,
        action: () => Promise<void>,
        messages?: { success?: string; error?: string },
      ) => {
        setPendingAction(actionId);
        try {
          await action();
          setMenuOpen(false);
          if (messages?.success) {
            toast.success(messages.success);
          }
        } catch (error) {
          console.error("Conversation action failed", error);
          toast.error(messages?.error ?? t("conversation_sidebar.action_failed_retry"));
        } finally {
          setPendingAction(null);
        }
      },
      [t],
    );
    return (
      <SidebarMenuItem>
        <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
          <SidebarMenuButton
            isActive={isActive}
            onClick={() => onSelect(conversation.id)}
            onContextMenu={(event) => {
              if (!hasMenuAction) return;
              event.preventDefault();
              setMenuOpen(true);
            }}
          >
            <span className="flex w-full items-center gap-2">
              <span className="flex-1 truncate">
                {conversation.title || t("conversation_sidebar.unnamed_conversation")}
              </span>
              {conversation.isPinned && <Pin className="size-3 text-primary" aria-hidden />}
              {conversation.isGenerating && (
                <span
                  className="inline-block size-2 rounded-full bg-emerald-500"
                  aria-label={t("conversation_sidebar.generating")}
                  title={t("conversation_sidebar.generating")}
                />
              )}
            </span>
          </SidebarMenuButton>

          {hasMenuAction && (
            <>
              <DropdownMenuTrigger asChild>
                <SidebarMenuAction
                  showOnHover
                  aria-label={t("conversation_sidebar.conversation_actions")}
                  title={t("conversation_sidebar.conversation_actions")}
                  disabled={pendingAction !== null}
                  onClick={(event) => {
                    event.stopPropagation();
                  }}
                >
                  <MoreHorizontal className="size-4" />
                </SidebarMenuAction>
              </DropdownMenuTrigger>
              <DropdownMenuContent side="right" align="start" className="w-48">
                {onPin && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      void runAction(
                        "pin",
                        async () => {
                          await onPin(conversation.id);
                        },
                        {
                          success: conversation.isPinned
                            ? t("conversation_sidebar.unpin_success")
                            : t("conversation_sidebar.pin_success"),
                          error: conversation.isPinned
                            ? t("conversation_sidebar.unpin_failed")
                            : t("conversation_sidebar.pin_failed"),
                        },
                      );
                    }}
                  >
                    {conversation.isPinned ? (
                      <PinOff className="size-4" />
                    ) : (
                      <Pin className="size-4" />
                    )}
                    <span>
                      {conversation.isPinned
                        ? t("conversation_sidebar.unpin")
                        : t("conversation_sidebar.pin")}
                    </span>
                  </DropdownMenuItem>
                )}

                {onRegenerateTitle && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      void runAction(
                        "regenerate-title",
                        async () => {
                          await onRegenerateTitle(conversation.id);
                        },
                        {
                          success: t("conversation_sidebar.regenerate_title_success"),
                          error: t("conversation_sidebar.regenerate_title_failed"),
                        },
                      );
                    }}
                  >
                    <RefreshCw className="size-4" />
                    <span>{t("conversation_sidebar.regenerate_title")}</span>
                  </DropdownMenuItem>
                )}

                {onUpdateTitle && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      const nextTitle = window
                        .prompt(t("conversation_sidebar.edit_title_prompt"), conversation.title)
                        ?.trim();
                      if (nextTitle == null) {
                        return;
                      }
                      if (nextTitle.length === 0) {
                        toast.error(t("conversation_sidebar.title_empty"));
                        return;
                      }
                      if (nextTitle === conversation.title) {
                        return;
                      }
                      void runAction(
                        "update-title",
                        async () => {
                          await onUpdateTitle(conversation.id, nextTitle);
                        },
                        {
                          success: t("conversation_sidebar.title_updated"),
                          error: t("conversation_sidebar.title_update_failed"),
                        },
                      );
                    }}
                  >
                    <Pencil className="size-4" />
                    <span>{t("conversation_sidebar.edit_title")}</span>
                  </DropdownMenuItem>
                )}

                {onMoveToAssistant && (
                  <DropdownMenuSub>
                    <DropdownMenuSubTrigger
                      disabled={pendingAction !== null || moveTargets.length === 0}
                    >
                      <MoveRight className="size-4" />
                      <span>{t("conversation_sidebar.move_to_assistant")}</span>
                    </DropdownMenuSubTrigger>
                    <DropdownMenuSubContent>
                      {moveTargets.length === 0 ? (
                        <DropdownMenuItem disabled>
                          {t("conversation_sidebar.no_available_assistants")}
                        </DropdownMenuItem>
                      ) : (
                        moveTargets.map((assistant) => (
                          <DropdownMenuItem
                            key={assistant.id}
                            disabled={pendingAction !== null}
                            onSelect={(event) => {
                              event.preventDefault();
                              void runAction(
                                `move:${assistant.id}`,
                                async () => {
                                  await onMoveToAssistant(conversation.id, assistant.id);
                                },
                                {
                                  success: t("conversation_sidebar.moved_to_assistant", {
                                    assistant: getAssistantDisplayName(assistant.name),
                                  }),
                                  error: t("conversation_sidebar.move_conversation_failed"),
                                },
                              );
                            }}
                          >
                            {getAssistantDisplayName(assistant.name)}
                          </DropdownMenuItem>
                        ))
                      )}
                    </DropdownMenuSubContent>
                  </DropdownMenuSub>
                )}

                {onMoveToFolder && (
                  <DropdownMenuSub>
                    <DropdownMenuSubTrigger disabled={pendingAction !== null}>
                      <FolderInput className="size-4" />
                      <span>{t("conversation_sidebar.move_to_folder")}</span>
                    </DropdownMenuSubTrigger>
                    <DropdownMenuSubContent>
                      <DropdownMenuItem
                        disabled={pendingAction !== null || !conversation.folderId}
                        onSelect={(event) => {
                          event.preventDefault();
                          void runAction(
                            "move-folder:none",
                            async () => {
                              await onMoveToFolder(conversation.id, null);
                            },
                            {
                              success: t("conversation_sidebar.moved_to_folder_none"),
                              error: t("conversation_sidebar.move_conversation_failed"),
                            },
                          );
                        }}
                      >
                        <span>{t("conversation_sidebar.remove_from_folder")}</span>
                      </DropdownMenuItem>
                      {folders.length > 0 && <DropdownMenuSeparator />}
                      {folders.map((folder) => (
                        <DropdownMenuItem
                          key={folder.id}
                          disabled={pendingAction !== null || folder.id === conversation.folderId}
                          onSelect={(event) => {
                            event.preventDefault();
                            void runAction(
                              `move-folder:${folder.id}`,
                              async () => {
                                await onMoveToFolder(conversation.id, folder.id);
                              },
                              {
                                success: t("conversation_sidebar.moved_to_folder", {
                                  folder: folder.name,
                                }),
                                error: t("conversation_sidebar.move_conversation_failed"),
                              },
                            );
                          }}
                        >
                          <Folder className="size-4" />
                          <span className="truncate">{folder.name}</span>
                          {folder.id === conversation.folderId && (
                            <Check className="ml-auto size-4" />
                          )}
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuSubContent>
                  </DropdownMenuSub>
                )}

                {onDelete && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                      variant="destructive"
                      disabled={pendingAction !== null}
                      onSelect={(event) => {
                        event.preventDefault();
                        if (!window.confirm(t("conversation_sidebar.delete_confirm"))) {
                          return;
                        }
                        void runAction(
                          "delete",
                          async () => {
                            await onDelete(conversation.id);
                          },
                          {
                            success: t("conversation_sidebar.delete_success"),
                            error: t("conversation_sidebar.delete_failed"),
                          },
                        );
                      }}
                    >
                      <Trash2 className="size-4" />
                      <span>{t("conversation_sidebar.delete_conversation")}</span>
                    </DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </>
          )}
        </DropdownMenu>
      </SidebarMenuItem>
    );
  },
);

