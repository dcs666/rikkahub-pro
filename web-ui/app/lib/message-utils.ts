import type { TFunction } from "i18next";

import type { UIMessagePart } from "~/types";

export function hasRenderablePart(part: UIMessagePart): boolean {
  switch (part.type) {
    case "text":
      return part.text.trim().length > 0;
    case "image":
    case "video":
    case "audio":
      return part.url.trim().length > 0;
    case "document":
      return part.url.trim().length > 0 || part.fileName.trim().length > 0;
    case "reasoning":
      return part.reasoning.trim().length > 0;
    case "tool":
      return true;
  }
}

export function formatPartForCopy(part: UIMessagePart, t: TFunction): string | null {
  switch (part.type) {
    case "text":
      return part.text;
    case "image":
      return `[${t("chat_message.copy_image")}] ${part.url}`;
    case "video":
      return `[${t("chat_message.copy_video")}] ${part.url}`;
    case "audio":
      return `[${t("chat_message.copy_audio")}] ${part.url}`;
    case "document":
      return `[${t("chat_message.copy_document")}] ${part.fileName}`;
    case "reasoning":
      return part.reasoning;
    case "tool":
      return `[${t("chat_message.copy_tool")}] ${part.toolName}`;
  }
}

export function buildCopyText(parts: UIMessagePart[], t: TFunction): string {
  return parts
    .map((part) => formatPartForCopy(part, t))
    .filter((value): value is string => Boolean(value && value.trim().length > 0))
    .join("\n\n")
    .trim();
}

export function hasEditableContent(parts: UIMessagePart[]): boolean {
  return parts.some(
    (part) =>
      part.type === "text" ||
      part.type === "image" ||
      part.type === "video" ||
      part.type === "audio" ||
      part.type === "document",
  );
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat().format(value);
}

export function getDurationMs(createdAt: string, finishedAt?: string | null): number | null {
  const start = Date.parse(createdAt);
  if (Number.isNaN(start)) return null;

  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  if (Number.isNaN(end) || end <= start) return null;

  return end - start;
}



export function parseToolOutputJson(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return null;

  try {
    return JSON.parse(trimmed);
  } catch {
    // Some models wrap JSON in fenced blocks.
    const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
    if (!fenced) return null;
    try {
      return JSON.parse(fenced[1]);
    } catch {
      return null;
    }
  }
}

export function buildCitationUrlMap(parts: UIMessagePart[]): Map<string, string> {
  const map = new Map<string, string>();

  parts.forEach((part) => {
    if (part.type !== "tool" || part.toolName !== "search_web") return;
    const outputText = part.output
      .filter((outputPart): outputPart is { type: "text"; text: string } => outputPart.type === "text")
      .map((outputPart) => outputPart.text)
      .join("\n");
    const parsed = parseToolOutputJson(outputText);
    if (!parsed || typeof parsed !== "object") return;
    const items = (parsed as { items?: unknown }).items;
    if (!Array.isArray(items)) return;

    items.forEach((item) => {
      if (!item || typeof item !== "object") return;
      const id = String((item as { id?: unknown }).id ?? "").trim();
      const url = String((item as { url?: unknown }).url ?? "").trim();
      if (!id || !url) return;
      if (!map.has(id)) {
        map.set(id, url);
      }
    });
  });

  return map;
}
