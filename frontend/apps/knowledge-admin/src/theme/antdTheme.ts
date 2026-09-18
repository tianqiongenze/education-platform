import { theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';
import { mapAliasToAntd, getToken } from '@ep/design-tokens';
import type { ThemeName } from '@ep/ui-kit';

/**
 * AntD 5 ConfigProvider theme derived from the @ep/design-tokens alias layer.
 *
 * The knowledge BU brand is purple (`color.brand.secondary` in the token
 * alias layer, light #722ed1 / dark #b37feb) while the shared alias default
 * maps `colorPrimary` to the platform blue — so we override the AntD primary
 * with the BU brand token, still fully token-driven and theme-aware.
 */
export function antdThemeConfig(theme: ThemeName): ThemeConfig {
  const token = { ...mapAliasToAntd(theme) };
  // The alias mapper returns literal-union AntD token types; the BU brand
  // override is still a token-driven hex value, so the cast is safe.
  token.colorPrimary = getToken('color.brand.secondary', theme) as typeof token.colorPrimary;
  token.colorLink = token.colorPrimary;
  return {
    token,
    algorithm: theme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
  };
}
