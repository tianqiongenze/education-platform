import { describe, it, expect } from 'vitest';
import { retry } from '../retry';

describe('retry', () => {
  it('succeeds on first try', async () => {
    let calls = 0;
    const result = await retry(async () => {
      calls++;
      return 'ok';
    });
    expect(result).toBe('ok');
    expect(calls).toBe(1);
  });

  it('retries then succeeds', async () => {
    let calls = 0;
    const result = await retry(
      async () => {
        calls++;
        if (calls < 2) {
          throw new Error('fail');
        }
        return 'ok';
      },
      { baseDelay: 1, maxDelay: 5 },
    );
    expect(result).toBe('ok');
    expect(calls).toBe(2);
  });

  it('fails after all retries', async () => {
    let calls = 0;
    await expect(
      retry(
        async () => {
          calls++;
          throw new Error('always fail');
        },
        { maxRetries: 2, baseDelay: 1, maxDelay: 5 },
      ),
    ).rejects.toThrow('always fail');
    expect(calls).toBe(3);
  });
});
