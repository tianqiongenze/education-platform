/**
 * Web platform adapter.
 *
 * Maps the Taro-style primitive component names (View, Text, Image, Video,
 * LivePlayer, Button) to their native HTML equivalents so that ui-kit can be
 * consumed by pure-web apps (admin-web, knowledge-admin) without pulling in
 * @tarojs/components.
 */
import type { FC } from 'react';

type ComponentLike = FC<Record<string, unknown>>;

export const View: ComponentLike = 'div' as unknown as ComponentLike;
export const Text: ComponentLike = 'span' as unknown as ComponentLike;
export const Image: ComponentLike = 'img' as unknown as ComponentLike;
export const Video: ComponentLike = 'video' as unknown as ComponentLike;
export const LivePlayer: ComponentLike = 'video' as unknown as ComponentLike;
export const Button: ComponentLike = 'button' as unknown as ComponentLike;
export const Input: ComponentLike = 'input' as unknown as ComponentLike;
