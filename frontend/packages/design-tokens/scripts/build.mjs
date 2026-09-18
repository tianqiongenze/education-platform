/**
 * Design tokens build script (zero runtime dependencies).
 *
 * Reads the three-layer W3C DTCG token sources:
 *   tokens/global.json    — atomic primitives
 *   tokens/alias.json     — semantic aliases (+ `dark.*` overrides)
 *   tokens/component.json — component-level tokens
 *
 * Emits:
 *   dist/tokens.css — `:root { --ep-* }` light values + `[data-theme="dark"]` overrides
 *   dist/tokens.ts  — type-safe flattened token maps + CSS var helpers +
 *                     `mapAliasToAntd()` for AntD 5 ConfigProvider theme.token
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const pkgRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const tokensDir = join(pkgRoot, 'tokens');
const distDir = join(pkgRoot, 'dist');

function readTokenFile(name) {
  return JSON.parse(readFileSync(join(tokensDir, name), 'utf8'));
}

const globalTree = readTokenFile('global.json');
const aliasRaw = readTokenFile('alias.json');
const componentTree = readTokenFile('component.json');

// ---------------------------------------------------------------------------
// Tree utilities
// ---------------------------------------------------------------------------

const META_KEYS = new Set(['$value', '$type', '$description', '$schema', '$extensions']);

/** True when a node is a concrete token (has $value). */
function isTokenNode(node) {
  return (
    typeof node === 'object' &&
    node !== null &&
    !Array.isArray(node) &&
    Object.prototype.hasOwnProperty.call(node, '$value')
  );
}

/**
 * Deep-merge `override` into `base`, returning a new tree.
 * Token nodes (with $value) from `override` replace `base` wholesale.
 */
function deepMerge(base, override) {
  const out = { ...base };
  for (const [key, value] of Object.entries(override ?? {})) {
    if (META_KEYS.has(key)) continue;
    const existing = out[key];
    if (
      typeof existing === 'object' &&
      existing !== null &&
      !isTokenNode(existing) &&
      typeof value === 'object' &&
      value !== null &&
      !isTokenNode(value)
    ) {
      out[key] = deepMerge(existing, value);
    } else {
      out[key] = value;
    }
  }
  return out;
}

/** Resolve `{a.b.c}` alias references against a merged lookup tree. */
function resolveValue(value, lookup, path, seen = new Set()) {
  if (typeof value !== 'string') return value;
  const match = /^\{([^}]+)\}$/.exec(value);
  if (!match) return value;
  const refPath = match[1];
  if (seen.has(refPath)) {
    throw new Error(`Circular token reference at "${path}": ${[...seen, refPath].join(' -> ')}`);
  }
  const target = getPath(lookup, refPath);
  if (target === undefined) {
    throw new Error(`Unresolved token reference "{${refPath}}" at "${path}"`);
  }
  return resolveValue(target, lookup, refPath, new Set([...seen, refPath]));
}

function getPath(tree, dotPath) {
  let node = tree;
  for (const segment of dotPath.split('.')) {
    if (typeof node !== 'object' || node === null || !(segment in node)) return undefined;
    node = node[segment];
  }
  return isTokenNode(node) ? node.$value : node;
}

/** Flatten a token tree to `{ 'dot.path': resolvedValue }`, resolving references. */
function flatten(tree, lookup, prefix = '', out = {}) {
  for (const [key, node] of Object.entries(tree)) {
    if (META_KEYS.has(key)) continue;
    if (typeof node !== 'object' || node === null) continue;
    const path = prefix ? `${prefix}.${key}` : key;
    if (isTokenNode(node)) {
      out[path] = resolveValue(node.$value, lookup, path);
    } else {
      flatten(node, lookup, path, out);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Build light + dark trees
// ---------------------------------------------------------------------------

const { dark: darkOverrides, ...lightAliases } = aliasRaw;
const lightTree = deepMerge(deepMerge(globalTree, lightAliases), componentTree);
const darkAliasTree = deepMerge(lightAliases, darkOverrides ?? {});
const darkTree = deepMerge(deepMerge(globalTree, darkAliasTree), componentTree);

/** Full flattened map (global + alias + component), references resolved. */
const lightFlat = flatten(lightTree, lightTree);
const darkFlat = flatten(darkTree, darkTree);

/** Theme-applied subset: semantic alias + component tokens only. */
const themeSource = { ...lightAliases, ...componentTree };
const themeLight = flatten(themeSource, lightTree);
const themeDark = flatten(deepMerge(themeSource, darkOverrides ?? {}), darkTree);

// ---------------------------------------------------------------------------
// CSS output
// ---------------------------------------------------------------------------

function cssVarName(dotPath) {
  return `--ep-${dotPath.replace(/\./g, '-')}`;
}

function toCssBlock(selector, entries) {
  const lines = entries.map(([path, value]) => `  ${cssVarName(path)}: ${value};`);
  return `${selector} {\n${lines.join('\n')}\n}\n`;
}

function buildCss() {
  const lightEntries = Object.entries(lightFlat);
  // Only emit dark values that actually differ from light (palette is shared).
  const darkEntries = Object.entries(darkFlat).filter(([path]) => {
    const fromAlias = themeDark[path] !== undefined || themeLight[path] !== undefined;
    return fromAlias && darkFlat[path] !== lightFlat[path];
  });
  const header = `/* Auto-generated by scripts/build.mjs — DO NOT EDIT.\n * Source of truth: packages/design-tokens/tokens/*.json (W3C DTCG).\n * Light values on :root; dark overrides under [data-theme="dark"].\n */\n`;
  return (
    header +
    toCssBlock(':root', lightEntries) +
    '\n' +
    toCssBlock('[data-theme="dark"]', darkEntries)
  );
}

// ---------------------------------------------------------------------------
// TypeScript output
// ---------------------------------------------------------------------------

function tsObject(flat) {
  const entries = Object.keys(flat)
    .sort()
    .map((path) => `  '${path}': ${JSON.stringify(flat[path])},`);
  return `{\n${entries.join('\n')}\n}`;
}

function buildTs() {
  return `// Auto-generated by scripts/build.mjs — DO NOT EDIT.
// Source of truth: packages/design-tokens/tokens/*.json (W3C DTCG).

export type ThemeName = 'light' | 'dark';

/** Full resolved light map (global + alias + component), keyed by dot path. */
export const tokens = ${tsObject(lightFlat)} as const;

/** Full resolved dark map (alias layer overridden by \`dark.*\`). */
export const darkTokens = ${tsObject(darkFlat)} as const;

/** Themable subset (alias + component layers) per theme. */
const themeLightTokens = ${tsObject(themeLight)} as const;
const themeDarkTokens = ${tsObject(themeDark)} as const;

export type TokenPath = keyof typeof tokens;

export function getToken(path: TokenPath, theme: ThemeName = 'light'): string {
  const map: Record<string, string> = theme === 'dark' ? darkTokens : tokens;
  const value = map[path];
  if (value === undefined) {
    throw new Error(\`Unknown design token: \${String(path)}\`);
  }
  return value;
}

/** CSS custom-property name for a token path, e.g. 'color.bg.page' -> '--ep-color-bg-page'. */
export function cssVarName(path: TokenPath): string {
  return \`--ep-\${String(path).replace(/\\./g, '-')}\`;
}

/** \`var(--ep-…)\` reference for use in inline styles. */
export function cssVar(path: TokenPath): string {
  return \`var(\${cssVarName(path)}, inherit)\`;
}

/** All themable CSS variables for a theme (alias + component layers). */
export function getCssVariables(theme: ThemeName): Record<string, string> {
  const source: Record<string, string> =
    theme === 'dark' ? themeDarkTokens : themeLightTokens;
  const out: Record<string, string> = {};
  for (const [path, value] of Object.entries(source)) {
    out[\`--ep-\${path.replace(/\\./g, '-')}\`] = value;
  }
  return out;
}

function pxToNumber(value: string | undefined, fallback: number): number {
  const n = value ? Number.parseFloat(value) : Number.NaN;
  return Number.isFinite(n) ? n : fallback;
}

/**
 * Map the alias layer onto AntD 5 ConfigProvider \`theme.token\`.
 * Use with \`<ConfigProvider theme={{ token: mapAliasToAntd(theme) }} />\`.
 */
export function mapAliasToAntd(theme: ThemeName) {
  const t = theme === 'dark' ? darkTokens : tokens;
  return {
    colorPrimary: t['color.brand.primary'],
    colorInfo: t['color.status.info'],
    colorSuccess: t['color.status.success'],
    colorWarning: t['color.status.warning'],
    colorError: t['color.status.error'],
    colorLink: t['color.text.link'],
    colorLinkHover: t['color.text.link.hover'],
    colorTextBase: t['color.text.primary'],
    colorTextSecondary: t['color.text.secondary'],
    colorTextTertiary: t['color.text.tertiary'],
    colorBgBase: t['color.bg.page'],
    colorBgContainer: t['color.bg.card'],
    colorBgLayout: t['color.bg.page'],
    colorBorder: t['color.border.base'],
    colorBorderSecondary: t['color.border.weakest'],
    borderRadius: pxToNumber(t['button.border.radius'], 6),
    borderRadiusLG: pxToNumber(t['card.border.radius'], 8),
    controlHeight: pxToNumber(t['button.height.md'], 32),
    fontSize: pxToNumber(t['font.size.md'], 14),
  } as const;
}

export type AntdTokenMap = ReturnType<typeof mapAliasToAntd>;
`;
}

// ---------------------------------------------------------------------------
// Emit
// ---------------------------------------------------------------------------

mkdirSync(distDir, { recursive: true });
writeFileSync(join(distDir, 'tokens.css'), buildCss(), 'utf8');
writeFileSync(join(distDir, 'tokens.ts'), buildTs(), 'utf8');

const lightCount = Object.keys(lightFlat).length;
const darkCount = Object.keys(darkFlat).length;
console.log(`[design-tokens] built dist/tokens.css + dist/tokens.ts (${lightCount} light / ${darkCount} dark tokens)`);
