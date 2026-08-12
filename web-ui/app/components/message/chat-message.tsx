import * as React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

import {
  ArrowDown,
  ArrowUp,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Copy,
  Ellipsis,
  FileDown,
  GitFork,
  Pencil,
  RefreshCw,
  Trash2,
  Zap,
} from "lucide-react";

import { useSettingsStore } from "~/stores";
import type {
  AssistantProfile,
  MessageDto,
  MessageNodeDto,
  ProviderModel,
  TokenUsage,
  UIMessagePart,
} from "~/types";

import { copyTextToClipboard } from "~/lib/clipboard";
import { convertMessageToMarkdown, downloadMarkdown } from "~/lib/export-markdown";
import { cn } from "~/lib/utils";
import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ChatMessageAnnotationsRow } from "./chat-message-annotations";
import { ChatMessageAvatarRow } from "./chat-message-avatar-row";
import { MessageParts } from "./message-part";
import {
  buildCitationUrlMap,
  buildCopyText,
  formatNumber,
  formatPartForCopy,
  getDurationMs,
  getNerdStats,
  hasEditableContent,
  hasRenderablePart,
  parseToolOutputJson,
} from "~/lib/message-utils";

interface ChatMessageProps {
  node: MessageNodeDto;
  message: MessageDto;
  loading?: boolean;
  isLastMessage?: boolean;
  assistant?: AssistantProfile | null;
  model?: ProviderModel | null;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
  onFork?: (messageId: string) => void | Promise<void>;
  onToolApproval?: (toolCallId: string, approved: boolean, reason: string, answer?: string) => void | Promise<void>;
}

const ChatMessageActionsRow = React.memo(({
  node,
  message,
  loading,
  alignRight,
  onEdit,
  onRegenerate,
  onSelectBranch,
  onDelete,
  onFork,
}: {
  node: MessageNodeDto;
  message: MessageDto;
  loading: boolean;
  alignRight: boolean;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
  onFork?: (messageId: string) => void | Promise<void>;
}) => {
  const { t } = useTranslation("message");
  const [regenerating, setRegenerating] = React.useState(false);
  const [switchingBranch, setSwitchingBranch] = React.useState(false);
  const [deleting, setDeleting] = React.useState(false);
  const [forking, setForking] = React.useState(false);

  const handleCopy = React.useCallback(async () => {
    const text = buildCopyText(message.parts, t);
    if (!text) return;

    try {
      await copyTextToClipboard(text);
    } catch {
      // Ignore copy failures to keep action row interaction uninterrupted.
    }
  }, [message.parts, t]);

  const handleRegenerate = React.useCallback(async () => {
    if (!onRegenerate) return;

    if (message.role === "USER") {
      const confirmed = window.confirm(t("chat_message.regenerate_from_user_confirm"));
      if (!confirmed) return;
    }

    setRegenerating(true);
    try {
      await onRegenerate(message.id);
    } finally {
      setRegenerating(false);
    }
  }, [message.id, message.role, onRegenerate, t]);

  const handleSwitchBranch = React.useCallback(
    async (selectIndex: number) => {
      if (!onSelectBranch) return;
      if (selectIndex < 0 || selectIndex > node.messages.length - 1) return;
      if (selectIndex === node.selectIndex) return;

      setSwitchingBranch(true);
      try {
        await onSelectBranch(node.id, selectIndex);
      } finally {
        setSwitchingBranch(false);
      }
    },
    [node.id, node.messages.length, node.selectIndex, onSelectBranch],
  );

  const handleDelete = React.useCallback(async () => {
    if (!onDelete) return;

    const confirmed = window.confirm(t("chat_message.delete_confirm"));
    if (!confirmed) return;

    setDeleting(true);
    try {
      await onDelete(message.id);
    } finally {
      setDeleting(false);
    }
  }, [message.id, onDelete, t]);

  const handleFork = React.useCallback(async () => {
    if (!onFork) return;

    setForking(true);
    try {
      await onFork(message.id);
    } finally {
      setForking(false);
    }
  }, [message.id, onFork]);

  const canSwitchBranch = Boolean(onSelectBranch) && node.messages.length > 1;
  const canEdit =
    Boolean(onEdit) &&
    (message.role === "USER" || message.role === "ASSISTANT") &&
    hasEditableContent(message.parts);
  const actionDisabled = loading || switchingBranch || regenerating || deleting || forking;

  return (
    <div
      className={cn(
        "flex w-full items-center gap-1 px-1",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      <Button
        aria-label={t("chat_message.copy_message")}
        disabled={actionDisabled}
        onClick={() => {
          void handleCopy();
        }}
        size="icon-xs"
        title={t("chat_message.copy")}
        type="button"
        variant="ghost"
      >
        <Copy className="size-3.5" />
      </Button>

      {canEdit && (
        <Button
          aria-label={t("chat_message.edit_message")}
          disabled={actionDisabled}
          onClick={() => {
            void onEdit?.(message);
          }}
          size="icon-xs"
          title={t("chat_message.edit")}
          type="button"
          variant="ghost"
        >
          <Pencil className="size-3.5" />
        </Button>
      )}

      {onRegenerate && (
        <Button
          aria-label={t("chat_message.regenerate")}
          disabled={actionDisabled}
          onClick={() => {
            void handleRegenerate();
          }}
          size="icon-xs"
          title={t("chat_message.regenerate")}
          type="button"
          variant="ghost"
        >
          <RefreshCw className={cn("size-3.5", regenerating && "animate-spin")} />
        </Button>
      )}

      {canSwitchBranch && (
        <>
          <Button
            aria-label={t("chat_message.previous_branch")}
            disabled={actionDisabled || node.selectIndex <= 0}
            onClick={() => {
              void handleSwitchBranch(node.selectIndex - 1);
            }}
            size="icon-xs"
            title={t("chat_message.previous_branch")}
            type="button"
            variant="ghost"
          >
            <ChevronLeft className="size-3.5" />
          </Button>
          <span className="text-[11px] text-muted-foreground">
            {node.selectIndex + 1}/{node.messages.length}
          </span>
          <Button
            aria-label={t("chat_message.next_branch")}
            disabled={actionDisabled || node.selectIndex >= node.messages.length - 1}
            onClick={() => {
              void handleSwitchBranch(node.selectIndex + 1);
            }}
            size="icon-xs"
            title={t("chat_message.next_branch")}
            type="button"
            variant="ghost"
          >
            <ChevronRight className="size-3.5" />
          </Button>
        </>
      )}

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            aria-label={t("chat_message.more_actions")}
            disabled={actionDisabled}
            size="icon-xs"
            title={t("chat_message.more_actions")}
            type="button"
            variant="ghost"
          >
            <Ellipsis className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align={alignRight ? "end" : "start"}>
          <DropdownMenuItem
            onSelect={() => {
              const content = convertMessageToMarkdown(message, false);
              downloadMarkdown(content, `message-${message.id}.md`);
            }}
          >
            <FileDown className="size-3.5" />
            {t("chat_message.export_markdown")}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() => {
              const content = convertMessageToMarkdown(message, true);
              downloadMarkdown(content, `message-${message.id}.md`);
            }}
          >
            <FileDown className="size-3.5" />
            {t("chat_message.export_markdown_with_reasoning")}
          </DropdownMenuItem>
          {onFork && (
            <DropdownMenuItem
              disabled={actionDisabled}
              onSelect={() => {
                void handleFork();
              }}
            >
              <GitFork className="size-3.5" />
              {t("chat_message.create_fork")}
            </DropdownMenuItem>
          )}
          {onDelete && (
            <DropdownMenuItem
              variant="destructive"
              disabled={actionDisabled}
              onSelect={() => {
                void handleDelete();
              }}
            >
              <Trash2 className="size-3.5" />
              {t("chat_message.delete")}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
});

const ChatMessageNerdLineRow = React.memo(({
  message,
  alignRight,
}: {
  message: MessageDto;
  alignRight: boolean;
}) => {
  const { t } = useTranslation("message");
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);

  if (!displaySetting?.showTokenUsage || !message.usage) {
    return null;
  }

  const stats = getNerdStats(message.usage, message.createdAt, message.finishedAt, t);
  if (stats.length === 0) return null;

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-1 text-[11px] text-muted-foreground/50",
        alignRight ? "justify-end" : "justify-start",
      )}
    >
      {stats.map((item) => (
        <div key={item.key} className="inline-flex items-center gap-1">
          {item.icon}
          <span>{item.label}</span>
        </div>
      ))}
    </div>
  );
});

export const ChatMessage = React.memo(({
  node,
  message,
  loading = false,
  isLastMessage = false,
  assistant,
  model,
  onEdit,
  onRegenerate,
  onSelectBranch,
  onDelete,
  onFork,
  onToolApproval,
}: ChatMessageProps) => {
  const isUser = message.role === "USER";
  const hasMessageContent = message.parts.some(hasRenderablePart);
  const showActions = isLastMessage ? !loading : hasMessageContent;
  const citationUrlMap = React.useMemo(() => buildCitationUrlMap(message.parts), [message.parts]);
  const handleClickCitation = React.useCallback(
    (citationId: string) => {
      const url = citationUrlMap.get(citationId.trim());
      if (!url || typeof window === "undefined") return;
      window.open(url, "_blank", "noopener,noreferrer");
    },
    [citationUrlMap],
  );

  return (
    <div
      className={cn("flex flex-col gap-4", isUser ? "items-end" : "items-start")}
      data-message-role={message.role.toLowerCase()}
      data-message-loading={loading || undefined}
    >
      <div className="flex w-full flex-col gap-2">
        <ChatMessageAvatarRow
          message={message}
          hasMessageContent={hasMessageContent}
          loading={loading}
          assistant={assistant}
          model={model}
        />

        <div className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}>
          <div
            data-message-bubble
            className={cn(
              "flex flex-col gap-2 text-sm",
              isUser ? "max-w-[85%] rounded-lg bg-muted px-4 py-3" : "w-full",
            )}
          >
            <MessageParts
              parts={message.parts}
              loading={loading}
              onToolApproval={onToolApproval}
              onClickCitation={handleClickCitation}
            />
          </div>
        </div>
      </div>

      {showActions && (
        <div data-message-actions>
          <ChatMessageActionsRow
            node={node}
            message={message}
            loading={loading}
            alignRight={isUser}
            onEdit={onEdit}
            onRegenerate={onRegenerate}
            onSelectBranch={onSelectBranch}
            onDelete={onDelete}
            onFork={onFork}
          />
        </div>
      )}

      <ChatMessageAnnotationsRow annotations={message.annotations} alignRight={isUser} />

      <ChatMessageNerdLineRow message={message} alignRight={isUser} />
    </div>
  );
});
