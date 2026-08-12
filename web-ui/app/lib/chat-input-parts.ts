import type { UIMessagePart, UploadFilesResponseDto } from "~/types";

/**
 * [拆分] chat-input 纯函数区（Strangler Fig）：无 React 依赖的附件/文件处理工具，
 * 从 components/input/chat-input.tsx 移出。partIcon（返回 JSX）留在原文件。
 */

export function toMessagePart(
  file: UploadFilesResponseDto["files"][number],
): UIMessagePart {
  if (file.mime.startsWith("image/")) {
    return {
      type: "image",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("video/")) {
    return {
      type: "video",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("audio/")) {
    return {
      type: "audio",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  return {
    type: "document",
    url: file.url,
    fileName: file.fileName,
    mime: file.mime,
    metadata: { fileId: file.id },
  };
}

export function partLabel(part: UIMessagePart, t: (key: string) => string): string {
  switch (part.type) {
    case "document":
      return part.fileName;
    case "image":
      return t("chat.attachment_image");
    case "video":
      return t("chat.attachment_video");
    case "audio":
      return t("chat.attachment_audio");
    default:
      return t("chat.attachment_file");
  }
}

export function getPartFileId(part: UIMessagePart): number | null {
  const value = part.metadata?.fileId;
  return typeof value === "number" ? value : null;
}

export function hasFilesInDataTransfer(dataTransfer: DataTransfer | null): boolean {
  if (!dataTransfer) return false;
  if (dataTransfer.files.length > 0) return true;
  return Array.from(dataTransfer.items).some((item) => item.kind === "file");
}
