/**
 * Platform adapter entry.
 *
 * At runtime we detect whether we are running inside a Taro **mini-program**
 * build by checking for the `TARO_ENV` define constant that Taro injects.
 * When the value is a mini-program platform (weapp, alipay, swan, tt, qq, jd,
 * harmony), the Taro primitives are used; otherwise — including H5 builds and
 * pure-web consumers (admin-web, knowledge-admin) — we fall back to native
 * HTML elements.
 *
 * H5 builds intentionally use the web adapter: Taro's H5 `@tarojs/components`
 * are Stencil web-component wrappers that add overhead and, under pnpm
 * isolation with React 19, webpack cannot always statically resolve every
 * named re-export (e.g. `Video`).  Native HTML elements (`<div>`, `<video>`,
 * …) are lighter, universally supported, and avoid the dual-package
 * resolution issue entirely.  Taro H5 apps render these native tags directly.
 *
 * The check is written so that bundlers can statically replace the condition
 * (Taro replaces `process.env.TARO_ENV` with a string literal) while still
 * evaluating correctly at runtime in plain Vite/Webpack builds.  We avoid
 * `require()` here so the module remains ESM-compatible for vitest/Vite.
 */
import * as web from './web';
import * as taro from './taro';

const MINI_PLATFORMS = ['weapp', 'alipay', 'swan', 'tt', 'qq', 'jd', 'harmony'];

const taroEnv =
  typeof process !== 'undefined' &&
  typeof process.env !== 'undefined' &&
  typeof (process.env as Record<string, unknown>).TARO_ENV === 'string'
    ? ((process.env as Record<string, string>).TARO_ENV as string)
    : '';

const isTaro = MINI_PLATFORMS.includes(taroEnv as string);

export { isTaro };

const mod = isTaro ? taro : web;

export const View = mod.View;
export const Text = mod.Text;
export const Image = mod.Image;
export const Video = mod.Video;
export const LivePlayer = mod.LivePlayer;
export const Button = mod.Button;
export const Input = mod.Input;
