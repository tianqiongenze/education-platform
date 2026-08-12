/**
 * Vitest setup for admin-web.
 *
 * jsdom does not implement window.matchMedia, which Ant Design's
 * ResponsiveObserver calls during component mount. Without this polyfill
 * every AntD component that uses Grid (Row/Col) crashes with
 * "TypeError: window.matchMedia is not a function".
 */
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// jsdom does not implement ResizeObserver either; AntD components
// (e.g. Table, Tabs) reference it lazily.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
global.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
