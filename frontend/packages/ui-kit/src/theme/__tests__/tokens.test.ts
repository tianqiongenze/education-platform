import { describe, it, expect } from 'vitest';
import { lightTheme, darkTheme } from '../tokens';

const REQUIRED_KEYS = [
  '--color-primary',
  '--color-primary-hover',
  '--color-success',
  '--color-warning',
  '--color-error',
  '--color-text-primary',
  '--color-text-secondary',
  '--color-bg-primary',
  '--color-bg-secondary',
  '--color-border',
];

describe('theme tokens', () => {
  it('lightTheme has all required CSS variables', () => {
    for (const key of REQUIRED_KEYS) {
      expect(lightTheme).toHaveProperty(key);
    }
  });

  it('darkTheme has all required CSS variables', () => {
    for (const key of REQUIRED_KEYS) {
      expect(darkTheme).toHaveProperty(key);
    }
  });

  it('all token values are non-empty strings', () => {
    for (const key of REQUIRED_KEYS) {
      const lightValue = lightTheme[key as keyof typeof lightTheme] ?? '';
      const darkValue = darkTheme[key as keyof typeof darkTheme] ?? '';
      expect(typeof lightValue).toBe('string');
      expect(lightValue.length).toBeGreaterThan(0);
      expect(typeof darkValue).toBe('string');
      expect(darkValue.length).toBeGreaterThan(0);
    }
  });
});
