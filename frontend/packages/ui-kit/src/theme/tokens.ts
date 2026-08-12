export const lightTheme = {
  '--color-primary': '#1890ff',
  '--color-primary-hover': '#40a9ff',
  '--color-success': '#52c41a',
  '--color-warning': '#faad14',
  '--color-error': '#ff4d4f',
  '--color-text-primary': '#262626',
  '--color-text-secondary': '#8c8c8c',
  '--color-bg-primary': '#ffffff',
  '--color-bg-secondary': '#f5f5f5',
  '--color-border': '#d9d9d9',
} as const;

export const darkTheme = {
  '--color-primary': '#177ddc',
  '--color-primary-hover': '#1389d6',
  '--color-success': '#389e0d',
  '--color-warning': '#d48806',
  '--color-error': '#cf1322',
  '--color-text-primary': '#e6e6e6',
  '--color-text-secondary': '#8c8c8c',
  '--color-bg-primary': '#1f1f1f',
  '--color-bg-secondary': '#2a2a2a',
  '--color-border': '#434343',
} as const;

export type ThemeName = 'light' | 'dark';
export type ThemeTokens = typeof lightTheme;
