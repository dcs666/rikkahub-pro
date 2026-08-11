import dayjs from "dayjs";
import type { TFunction } from "i18next";

import type { ConversationListDto } from "~/types";

// ==================== [F7] 会话侧栏纯函数（自 conversation-sidebar.tsx 拆出） ====================

export type ConversationListItem =
  | { type: "pinned-header" }
  | { type: "date-header"; date: string; label: string }
  | { type: "item"; conversation: ConversationListDto };

export function getDateLabel(date: dayjs.Dayjs, t: TFunction): string {
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");

  if (date.isSame(today, "day")) return t("conversation_sidebar.today");
  if (date.isSame(yesterday, "day")) return t("conversation_sidebar.yesterday");

  const native = date.toDate();
  const sameYear = date.year() === today.year();
  const formatter = new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  });
  return formatter.format(native);
}

export function groupConversations(
  conversations: ConversationListDto[],
  t: TFunction,
): ConversationListItem[] {
  const items: ConversationListItem[] = [];
  const pinned = conversations.filter((c) => c.isPinned);
  const unpinned = conversations.filter((c) => !c.isPinned);

  if (pinned.length > 0) {
    items.push({ type: "pinned-header" });
    for (const c of pinned) {
      items.push({ type: "item", conversation: c });
    }
  }

  let lastDate: string | null = null;
  for (const c of unpinned) {
    const date = dayjs(c.updateAt).startOf("day");
    const dateKey = date.format("YYYY-MM-DD");
    if (dateKey !== lastDate) {
      items.push({ type: "date-header", date: dateKey, label: getDateLabel(date, t) });
      lastDate = dateKey;
    }
    items.push({ type: "item", conversation: c });
  }

  return items;
}
