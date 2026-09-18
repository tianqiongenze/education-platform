/**
 * Theme token bridge.
 *
 * The source of truth for design tokens is now `@ep/design-tokens`
 * (three-layer W3C DTCG: global -> alias -> component). This module keeps the
 * legacy `lightTheme` / `darkTheme` CSS-variable maps alive for a transition
 * period; new code should import from `@ep/design-tokens` directly.
 */
import { tokens, darkTokens, getCssVariables } from '@ep/design-tokens';

export type ThemeName = 'light' | 'dark';

export { tokens as designTokens, darkTokens as designDarkTokens, getCssVariables };

/**
 * @deprecated Legacy CSS-variable map. Keys are kept for backward
 * compatibility; values are derived from the new alias layer.
 */
export const lightTheme: Record<string, string> = {
  '--color-primary': tokens['color.brand.primary'],
  '--color-primary-hover': tokens['color.brand.primary.hover'],
  '--color-success': tokens['color.status.success'],
  '--color-warning': tokens['color.status.warning'],
  '--color-error': tokens['color.status.error'],
  '--color-text-primary': tokens['color.text.primary'],
  '--color-text-secondary': tokens['color.text.secondary'],
  '--color-bg-primary': tokens['color.bg.card'],
  '--color-bg-secondary': tokens['color.bg.secondary'],
  '--color-border': tokens['color.border.base'],
};

/** @deprecated Legacy dark-mode CSS-variable map. */
export const darkTheme: Record<string, string> = {
  '--color-primary': darkTokens['color.brand.primary'],
  '--color-primary-hover': darkTokens['color.brand.primary.hover'],
  '--color-success': darkTokens['color.status.success'],
  '--color-warning': darkTokens['color.status.warning'],
  '--color-error': darkTokens['color.status.error'],
  '--color-text-primary': darkTokens['color.text.primary'],
  '--color-text-secondary': darkTokens['color.text.secondary'],
  '--color-bg-primary': darkTokens['color.bg.card'],
  '--color-bg-secondary': darkTokens['color.bg.secondary'],
  '--color-border': darkTokens['color.border.base'],
};

export type ThemeTokens = typeof lightTheme;
