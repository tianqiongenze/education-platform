import { describe, it, expect } from 'vitest';
import {
  tokens,
  darkTokens,
  getToken,
  cssVarName,
  cssVar,
  getCssVariables,
  mapAliasToAntd,
} from '../../dist/tokens';

describe('design tokens (built artifacts)', () => {
  it('contains the required brand + semantic aliases', () => {
    // Brand blue #1890ff family and purple #722ed1 family.
    expect(tokens['color.blue.4']).toBe('#1890ff');
    expect(tokens['color.purple.5']).toBe('#722ed1');
    // Semantic layer resolves to palette values.
    expect(tokens['color.bg.page']).toBe('#f5f5f5');
    expect(tokens['color.bg.card']).toBe('#ffffff');
    expect(tokens['color.text.primary']).toBe('#262626');
    expect(tokens['color.text.secondary']).toBe('#8c8c8c');
    expect(tokens['color.text.link']).toBe('#1890ff');
    expect(tokens['color.border.base']).toBe('#d9d9d9');
  });

  it('includes 30+ palette colors, 8 spacing / font-size steps, 4 radius levels', () => {
    const palette = Object.keys(tokens).filter((k) => /^color\.(blue|purple|cyan|green|orange|red|magenta|gray)\./.test(k));
    expect(palette.length).toBeGreaterThanOrEqual(30);
    expect(Object.keys(tokens).filter((k) => /^space\.\d$/.test(k))).toHaveLength(8);
    expect(Object.keys(tokens).filter((k) => /^font\.size\.\d$/.test(k))).toHaveLength(8);
    expect(Object.keys(tokens).filter((k) => /^radius\.\d$/.test(k))).toHaveLength(4);
    expect(Object.keys(tokens).filter((k) => /^shadow\.\d$/.test(k))).toHaveLength(4);
    expect(tokens['motion.duration.fast']).toBeDefined();
    expect(tokens['motion.easing.standard']).toBeDefined();
  });

  it('dark aliases override light values', () => {
    expect(darkTokens['color.bg.page']).not.toBe(tokens['color.bg.page']);
    expect(darkTokens['color.bg.page']).toBe('#141414');
    expect(darkTokens['color.bg.card']).toBe('#1f1f1f');
    expect(darkTokens['color.text.primary']).toBe('#e6e6e6');
    // Global palette is theme-invariant.
    expect(darkTokens['color.blue.4']).toBe(tokens['color.blue.4']);
  });

  it('component tokens resolve nested alias references', () => {
    expect(tokens['button.height.md']).toBe('32px');
    expect(tokens['card.padding']).toBe('16px');
    expect(getToken('card.border.radius')).toBe('6px');
  });

  it('cssVarName/cssVar produce --ep-* references', () => {
    expect(cssVarName('color.bg.page')).toBe('--ep-color-bg-page');
    expect(cssVar('color.brand.primary')).toBe('var(--ep-color-brand-primary, inherit)');
  });

  it('getCssVariables returns flattened --ep-* variables per theme', () => {
    const light = getCssVariables('light');
    const dark = getCssVariables('dark');
    expect(light['--ep-color-bg-page']).toBe('#f5f5f5');
    expect(dark['--ep-color-bg-page']).toBe('#141414');
    expect(Object.keys(dark).length).toBeGreaterThan(0);
  });

  it('mapAliasToAntd produces an AntD 5 theme.token mapping', () => {
    const light = mapAliasToAntd('light');
    const dark = mapAliasToAntd('dark');
    expect(light.colorPrimary).toBe('#1890ff');
    expect(light.colorBgLayout).toBe('#f5f5f5');
    expect(light.borderRadius).toBe(6);
    expect(light.controlHeight).toBe(32);
    expect(light.fontSize).toBe(14);
    expect(dark.colorBgLayout).toBe('#141414');
    expect(dark.colorPrimary).not.toBe(light.colorPrimary);
  });
});
