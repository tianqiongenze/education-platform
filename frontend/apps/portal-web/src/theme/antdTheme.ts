import { theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';
import { mapAliasToAntd } from '@ep/design-tokens';
import type { ThemeName } from '@ep/ui-kit';

/**
 * AntD 5 ConfigProvider theme derived from the @ep/design-tokens alias layer.
 *
 * The portal brand follows the platform primary (#1890ff family) so the
 * shared alias mapping is used as-is; dark mode swaps in the alias layer's
 * dark overrides plus AntD's dark seed algorithm.
 */
export function antdThemeConfig(theme: ThemeName): ThemeConfig {
  return {
    token: { ...mapAliasToAntd(theme) },
    algorithm: theme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
  };
}
