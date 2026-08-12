import {
  bundledLanguages,
  createHighlighter,
  type BundledLanguage,
  type BundledTheme,
  type HighlighterGeneric,
  type ThemedToken,
} from "shiki";

export interface TokenizedCode {
  bg: string;
  fg: string;
  tokens: ThemedToken[][];
}

export function toDownloadFileName(language: string): string {
  const normalized = language.trim().toLowerCase();
  if (!normalized) {
    return DEFAULT_DOWNLOAD_FILE_NAME;
  }

  const mappedExtension = CODE_LANGUAGE_EXTENSION_MAP[normalized];
  if (mappedExtension) {
    return `code.${mappedExtension}`;
  }

  const safeExtension = normalized.replace(/[^a-z0-9]+/g, "");
  if (!safeExtension) {
    return DEFAULT_DOWNLOAD_FILE_NAME;
  }

  return `code.${safeExtension}`;
}

const highlighterCache = new Map<
  BundledLanguage,
  Promise<HighlighterGeneric<BundledLanguage, BundledTheme>>
>();
const resolvedHighlighters = new Map<
  BundledLanguage,
  HighlighterGeneric<BundledLanguage, BundledTheme>
>();
const tokensCache = new Map<string, TokenizedCode>();
const subscribers = new Map<string, Set<(result: TokenizedCode) => void>>();

export function resolveShikiLanguage(language: string): BundledLanguage | null {
  const normalized = language.trim().toLowerCase();
  if (!normalized) {
    return null;
  }

  if (!Object.prototype.hasOwnProperty.call(bundledLanguages, normalized)) {
    return null;
  }

  return normalized as BundledLanguage;
}

export function getTokensCacheKey(code: string, language: BundledLanguage): string {
  return `${language}\u0000${code}`;
}

export function readTokensFromCache(cacheKey: string): TokenizedCode | null {
  const cached = tokensCache.get(cacheKey);
  if (!cached) {
    return null;
  }

  tokensCache.delete(cacheKey);
  tokensCache.set(cacheKey, cached);
  return cached;
}

export function writeTokensToCache(cacheKey: string, tokenized: TokenizedCode): void {
  if (tokensCache.size >= SHIKI_CACHE_LIMIT) {
    const oldest = tokensCache.keys().next().value;
    if (typeof oldest === "string") {
      tokensCache.delete(oldest);
    }
  }

  tokensCache.set(cacheKey, tokenized);
}

export function getHighlighter(
  language: BundledLanguage,
): Promise<HighlighterGeneric<BundledLanguage, BundledTheme>> {
  const cached = highlighterCache.get(language);
  if (cached) {
    return cached;
  }

  const highlighterPromise = createHighlighter({
    langs: [language],
    themes: [SHIKI_THEME_LIGHT, SHIKI_THEME_DARK],
  });
  highlighterCache.set(language, highlighterPromise);
  return highlighterPromise;
}

export function createRawTokens(code: string): TokenizedCode {
  return {
    bg: "transparent",
    fg: "inherit",
    tokens: code.split("\n").map((line) =>
      line === ""
        ? []
        : [
            {
              color: "inherit",
              content: line,
            } as ThemedToken,
          ],
    ),
  };
}

export function addKeysToTokens(lines: ThemedToken[][]): KeyedLine[] {
  return lines.map((line, lineIndex) => ({
    key: `line-${lineIndex}`,
    tokens: line.map((token, tokenIndex) => ({
      key: `line-${lineIndex}-${tokenIndex}`,
      token,
    })),
  }));
}

export function isItalic(fontStyle: number | undefined): boolean {
  return ITALIC_STYLES.has(fontStyle ?? 0);
}

export function isBold(fontStyle: number | undefined): boolean {
  return BOLD_STYLES.has(fontStyle ?? 0);
}

export function isUnderline(fontStyle: number | undefined): boolean {
  return UNDERLINE_STYLES.has(fontStyle ?? 0);
}

export function highlightCode(
  code: string,
  language: BundledLanguage,
  callback?: (result: TokenizedCode) => void,
): TokenizedCode | null {
  const tokensCacheKey = getTokensCacheKey(code, language);
  const cached = readTokensFromCache(tokensCacheKey);
  if (cached) {
    return cached;
  }

  // Synchronous path: if the highlighter is already loaded, highlight immediately
  const resolved = resolvedHighlighters.get(language);
  if (resolved) {
    const tokenResult = resolved.codeToTokens(code, {
      lang: language,
      themes: {
        light: SHIKI_THEME_LIGHT,
        dark: SHIKI_THEME_DARK,
      },
    });

    const tokenized: TokenizedCode = {
      bg: tokenResult.bg ?? "transparent",
      fg: tokenResult.fg ?? "inherit",
      tokens: tokenResult.tokens,
    };

    writeTokensToCache(tokensCacheKey, tokenized);
    return tokenized;
  }

  // Async path: first time loading this language's highlighter
  if (callback) {
    if (!subscribers.has(tokensCacheKey)) {
      subscribers.set(tokensCacheKey, new Set());
    }
    subscribers.get(tokensCacheKey)?.add(callback);
  }

  void getHighlighter(language)
    .then((highlighter) => {
      resolvedHighlighters.set(language, highlighter);

      const tokenResult = highlighter.codeToTokens(code, {
        lang: language,
        themes: {
          light: SHIKI_THEME_LIGHT,
          dark: SHIKI_THEME_DARK,
        },
      });

      const tokenized: TokenizedCode = {
        bg: tokenResult.bg ?? "transparent",
        fg: tokenResult.fg ?? "inherit",
        tokens: tokenResult.tokens,
      };

      writeTokensToCache(tokensCacheKey, tokenized);
      const subs = subscribers.get(tokensCacheKey);
      if (subs) {
        for (const sub of subs) {
          sub(tokenized);
        }
        subscribers.delete(tokensCacheKey);
      }
    })
    .catch((e) => {
      const fallback = createRawTokens(code);
      writeTokensToCache(tokensCacheKey, fallback);
      const subs = subscribers.get(tokensCacheKey);
      if (subs) {
        for (const sub of subs) {
          sub(fallback);
        }
        subscribers.delete(tokensCacheKey);
      }
    });

  return null;
}

const LINE_NUMBER_CLASSES = cn(
  "block",
  "before:mr-4",
  "before:inline-block",
  "before:w-8",
  "before:text-right",
  "before:font-mono",
  "before:text-muted-foreground/50",
  "before:select-none",
  "before:content-[counter(line)]",
  "before:[counter-increment:line]",
);
