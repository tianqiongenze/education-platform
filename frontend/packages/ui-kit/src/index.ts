// Side-effect import: injects the design-token CSS custom properties
// (--ep-* variables, light on :root, dark overrides under [data-theme="dark"]).
// Consumers that already inject the variables via <ThemeProvider /> are
// unaffected — these declarations only set defaults that the provider can
// override at runtime.
import '@ep/design-tokens/tokens.css';

export * from './components';
export * from './theme';
export { classNames } from './utils/classNames';
