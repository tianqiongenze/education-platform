import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { STORAGE_KEYS } from '@ep/shared';
import { getCssVariables } from '@ep/design-tokens';
import { lightTheme, darkTheme } from './tokens';
import type { ThemeName } from './tokens';

interface ThemeContextValue {
  theme: ThemeName;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'light',
  toggle: () => {},
});

// Graceful storage fallback: prefer localStorage, then an in-memory no-op
// object when it is unavailable (e.g. mini-program environment).
const memoryStore = new Map<string, string>();

const storage = {
  get(key: string): string | undefined {
    try {
      if (typeof localStorage !== 'undefined') {
        return localStorage.getItem(key) ?? undefined;
      }
    } catch {
      // ignore (e.g. MP environment without localStorage)
    }
    return memoryStore.get(key);
  },
  set(key: string, value: string): void {
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(key, value);
      }
    } catch {
      // ignore
    }
    memoryStore.set(key, value);
  },
};

function applyCssVars(theme: ThemeName): void {
  // Browser/H5: inject the design-token CSS variables into the document root
  // and flag the theme for `[data-theme="dark"]` CSS overrides. In MP there is
  // no document, so this is a safe no-op (platform styling is used instead).
  if (typeof document === 'undefined') return;

  const root = document.documentElement;
  root.setAttribute('data-theme', theme);

  const cssVars = getCssVariables(theme);
  Object.entries(cssVars).forEach(([key, val]) => {
    root.style.setProperty(key, val);
  });

  // Legacy variable names — kept during the migration to @ep/design-tokens.
  const legacyVars = theme === 'dark' ? darkTheme : lightTheme;
  Object.entries(legacyVars).forEach(([key, val]) => {
    root.style.setProperty(key, val);
  });
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<ThemeName>(() => {
    const stored = storage.get(STORAGE_KEYS.THEME);
    return stored === 'dark' ? 'dark' : 'light';
  });

  useEffect(() => {
    applyCssVars(theme);
    storage.set(STORAGE_KEYS.THEME, theme);
  }, [theme]);

  const toggle = () => {
    setTheme((t) => (t === 'light' ? 'dark' : 'light'));
  };

  return (
    <ThemeContext.Provider value={{ theme, toggle }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
