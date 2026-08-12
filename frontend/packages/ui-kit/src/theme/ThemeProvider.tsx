import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { STORAGE_KEYS } from '@ep/shared';
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

// Graceful storage fallback: prefer Taro storage, fall back to localStorage,
// then to an in-memory no-op object when neither is available.
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
  // H5: inject CSS variables into document root.
  // MP: not applicable (CSS variables handled via platform styling).
  if (process.env.TARO_ENV !== 'h5') return;
  if (typeof document === 'undefined') return;

  const cssVars = theme === 'dark' ? darkTheme : lightTheme;
  Object.entries(cssVars).forEach(([key, val]) => {
    document.documentElement.style.setProperty(key, val);
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
