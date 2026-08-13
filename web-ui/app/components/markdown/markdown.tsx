import * as React from "react";
import { useTranslation } from "react-i18next";
import { Streamdown } from "streamdown";
import { cjk } from "@streamdown/cjk";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import { cn } from "~/lib/utils";
import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { useOptionalWorkbench } from "~/components/workbench/workbench-context";
import { useSettingsStore } from "~/stores";
import { CodeBlock } from "./code-block";
import "katex/dist/katex.min.css";
// mhchem 扩展: 支持 \ce{} 等化学公式语法
import "katex/dist/contrib/mhchem.mjs";
import "./markdown.css";
import "streamdown/styles.css";

// Regex patterns for preprocessing
const INLINE_LATEX_REGEX = /\\\((.+?)\\\)/g;
const BLOCK_LATEX_REGEX = /\\\[(.+?)\\\]/gs;
const CODE_BLOCK_REGEX = /```[\s\S]*?```|`[^`\n]*`/g;

// Preprocess markdown content
function preProcess(content: string): string {
  // Find all code block positions
  const codeBlocks: { start: number; end: number }[] = [];
  let match;
  const codeBlockRegex = new RegExp(CODE_BLOCK_REGEX.source, "g");
  while ((match = codeBlockRegex.exec(content)) !== null) {
    codeBlocks.push({ start: match.index, end: match.index + match[0].length });
  }

  // Check if position is inside a code block
  const isInCodeBlock = (position: number): boolean => {
    return codeBlocks.some((range) => position >= range.start && position < range.end);
  };

  // Replace inline formulas \( ... \) to $ ... $, skip code blocks
  let result = content.replace(
    new RegExp(INLINE_LATEX_REGEX.source, "g"),
    (match, group1, offset) => {
      if (isInCodeBlock(offset)) {
        return match;
      }
      return `$${group1}$`;
    },
  );

  // Replace block formulas \[ ... \] to $$ ... $$, skip code blocks
  result = result.replace(new RegExp(BLOCK_LATEX_REGEX.source, "gs"), (match, group1, offset) => {
    if (isInCodeBlock(offset)) {
      return match;
    }
    return `$$${group1}$$`;
  });

  return result;
}

type MarkdownProps = {
  content: string;
  className?: string;
  onClickCitation?: (id: string) => void;
  allowCodePreview?: boolean;
  isAnimating?: boolean;
};

// [SECURITY] rehype-sanitize 清洗 LLM 输出中的原始 HTML（rehypeRaw 会解析任意 HTML）：
// - 默认 schema 已禁 script/iframe/object/embed/form/meta/link/base 等危险标签
// - 默认 protocols 已禁 javascript:/data: 等危险 href（防点击执行/钓鱼）
// - 定制保留：className（katex 公式体系 / citation-badge / code language-* 高亮需要）、
//   style（katex strut 行高内联样式需要——残余 CSS 注入面接受，内容源为可信 LLM 输出）
const MARKDOWN_SANITIZE_SCHEMA = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    "*": ["className", "style"],
    a: [...(defaultSchema.attributes.a ?? ["href", "title"]), "target", "rel"],
    img: [...(defaultSchema.attributes.img ?? ["src", "alt", "title"]), "loading"],
  },
};

function getNodeText(node: React.ReactNode): string {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(getNodeText).join("");
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) {
    return getNodeText(node.props.children);
  }
  return "";
}

export default function Markdown({
  content,
  className,
  onClickCitation,
  allowCodePreview = true,
  isAnimating = false,
}: MarkdownProps) {
  const { t } = useTranslation("markdown");
  const workbench = useOptionalWorkbench();
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);
  const processedContent = React.useMemo(() => preProcess(content), [content]);
  const handlePreviewCode = React.useCallback(
    (language: string, code: string) => {
      if (!allowCodePreview || !workbench) return;

      const previewLanguage = getCodePreviewLanguage(language);
      if (!previewLanguage) return;

      workbench.openPanel({
        type: "code-preview",
        title: t("markdown.code_preview_title", {
          language: previewLanguage.toUpperCase(),
        }),
        payload: {
          language: previewLanguage,
          code,
        },
      });
    },
    [allowCodePreview, t, workbench],
  );

  return (
    <div className={cn("markdown", className)}>
      <Streamdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[
          [rehypeKatex, { trust: true, strict: false }],
          rehypeRaw,
          [rehypeSanitize, MARKDOWN_SANITIZE_SCHEMA],
        ]}
        plugins={{ cjk: cjk }}
        animated={{ animation: "fadeIn", sep: 'word', duration: 150 }}
        isAnimating={isAnimating}
        controls={{code: false, mermaid: false}}
        components={{
          pre: ({ children }) => <>{children}</>,
          code: ({ className, children, ...props }) => {
            const match = /language-([A-Za-z0-9_-]+)/.exec(className || "");
            const code = String(children).replace(/\n$/, "");
            const isBlock = code.includes("\n");

            if (match || isBlock) {
              const language = match?.[1] || "";
              return (
                <CodeBlock
                  language={language}
                  code={code}
                  showLineNumbers={displaySetting?.showLineNumbers ?? false}
                  wrapLines={displaySetting?.codeBlockAutoWrap ?? false}
                  onPreview={
                    allowCodePreview && workbench
                      ? () => {
                          handlePreviewCode(language, code);
                        }
                      : undefined
                  }
                />
              );
            }

            return (
              <code className="inline-code" {...props}>
                {children}
              </code>
            );
          },
          a: ({ href, children, ...props }) => {
            const childText = getNodeText(children).trim();

            // Citation format: [citation,domain](id)
            if (childText.startsWith("citation,")) {
              const domain = childText.substring("citation,".length);
              const id = (href || "").trim();

              if (id.length === 6) {
                return (
                  <span
                    className="citation-badge"
                    onClick={() => onClickCitation?.(id)}
                    title={domain}
                  >
                    {domain}
                  </span>
                );
              }

              if (href) {
                return (
                  <a
                    className="citation-badge"
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={domain}
                    {...props}
                  >
                    {domain}
                  </a>
                );
              }
            }

            return (
              <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
                {children}
              </a>
            );
          },
        }}
      >
        {processedContent}
      </Streamdown>
    </div>
  );
}
