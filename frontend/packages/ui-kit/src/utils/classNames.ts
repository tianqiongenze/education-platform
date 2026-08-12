import clsx from 'clsx';

export function classNames(...args: Parameters<typeof clsx>): string {
  return clsx(args);
}
