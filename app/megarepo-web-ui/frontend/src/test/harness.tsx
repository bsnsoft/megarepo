import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { ToastProvider } from '../components/Toast';

/**
 * Test harness for the admin screens.
 *
 * The pages are mounted whole and driven through `fetch`, not through a mocked
 * API module. That is a deliberate trade: it is slightly more setup, and it
 * means a test fails when a page asks for the wrong URL — which for this feature
 * is the failure most worth catching, because half the endpoints were written by
 * a sibling package and the paths are the contract between them.
 */

export interface RouteHandler {
  /** Substring or RegExp the request URL has to match. */
  match: string | RegExp;
  method?: string;
  status?: number;
  body?: unknown;
  /**
   * Answers consumed in order, the last one repeating once the list runs out.
   * For the flows where the second attempt is supposed to behave differently
   * from the first — a write refused for a missing confirmation, then accepted
   * with one.
   */
  responses?: { status?: number; body?: unknown }[];
}

export interface MockFetch {
  /** Every request the component made, in order. */
  calls: { url: string; method: string; body: unknown }[];
  handlers: RouteHandler[];
}

function matches(handler: RouteHandler, url: string, method: string): boolean {
  if (handler.method && handler.method.toUpperCase() !== method.toUpperCase()) {
    return false;
  }
  return typeof handler.match === 'string' ? url.includes(handler.match) : handler.match.test(url);
}

/**
 * Installs a `fetch` that answers from `handlers`, last match winning so a test
 * can override a default set up by its own helper. An unmatched request answers
 * 404 rather than hanging — a silent pending promise is the hardest test failure
 * to read.
 */
export function mockFetch(handlers: RouteHandler[]): MockFetch {
  const state: MockFetch = { calls: [], handlers: [...handlers] };
  const consumed = new Map<RouteHandler, number>();

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = (init?.method ?? 'GET').toUpperCase();
      let parsed: unknown = null;
      if (typeof init?.body === 'string') {
        try {
          parsed = JSON.parse(init.body);
        } catch {
          parsed = init.body;
        }
      }
      state.calls.push({ url, method, body: parsed });

      const handler = [...state.handlers].reverse().find((candidate) => matches(candidate, url, method));
      if (!handler) {
        return new Response(JSON.stringify({ status: 404, message: `no handler for ${method} ${url}` }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      let answer: { status?: number; body?: unknown } = handler;
      if (handler.responses && handler.responses.length > 0) {
        const seen = consumed.get(handler) ?? 0;
        answer = handler.responses[Math.min(seen, handler.responses.length - 1)];
        consumed.set(handler, seen + 1);
      }

      const status = answer.status ?? 200;
      if (status === 204) {
        return new Response(null, { status });
      }
      return new Response(JSON.stringify(answer.body ?? {}), {
        status,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );

  return state;
}

/** Adds (or overrides) a handler after the initial render. */
export function addHandler(mock: MockFetch, handler: RouteHandler): void {
  mock.handlers.push(handler);
}

export function renderPage(element: ReactElement, { path = '/', route }: { path?: string; route?: string } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        {route ? (
          <Routes>
            <Route path={route} element={element} />
          </Routes>
        ) : (
          element
        )}
      </ToastProvider>
    </MemoryRouter>,
  );
}
