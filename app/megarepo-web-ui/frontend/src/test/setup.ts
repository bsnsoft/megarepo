import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

/**
 * jsdom's own `localStorage` is not usable in every Node/jsdom combination this
 * repo gets built on — it needs a backing file Node only wires up when asked to.
 * The API client reads a token out of it at module load, so a broken store makes
 * every component test fail at `import`, before anything is rendered. An
 * in-memory replacement is installed here rather than each test faking one,
 * because the failure is an environment quirk and not something a test should
 * have an opinion about.
 */
function installMemoryStorage(): void {
  try {
    if (typeof window.localStorage?.getItem === 'function') {
      return;
    }
  } catch {
    // Accessing it can throw as well; fall through and replace it.
  }
  const store = new Map<string, string>();
  const memory: Storage = {
    get length() {
      return store.size;
    },
    clear: () => store.clear(),
    getItem: (key: string) => (store.has(key) ? (store.get(key) as string) : null),
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    removeItem: (key: string) => {
      store.delete(key);
    },
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
  };
  Object.defineProperty(window, 'localStorage', { value: memory, configurable: true });
  Object.defineProperty(globalThis, 'localStorage', { value: memory, configurable: true });
}

installMemoryStorage();

afterEach(() => {
  cleanup();
});
