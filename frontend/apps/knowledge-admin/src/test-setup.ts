/**
 * Vitest setup for knowledge-admin.
 *
 * jsdom does not implement window.matchMedia, which Ant Design's
 * ResponsiveObserver calls during component mount.
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

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
global.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
