import { theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';
import { mapAliasToAntd } from '@ep/design-tokens';
import type { ThemeName } from '@ep/ui-kit';

/**
 * AntD 5 ConfigProvider theme derived from the @ep/design-tokens alias layer.
 *
 * The design-tokens package is the single source of truth: light values come
 * from the alias layer, dark values from its `dark.*` overrides (same
 * overrides that drive the --ep-* CSS variables via <ThemeProvider>).
 * AntD additionally gets the matching seed algorithm so component surfaces
 * (menus, tables, popovers) follow the mode even for tokens the alias layer
 * does not pin down.
 */
export function antdThemeConfig(theme: ThemeName): ThemeConfig {
  return {
    token: { ...mapAliasToAntd(theme) },
    algorithm: theme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
  };
}
