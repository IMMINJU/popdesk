// The fake network for the console screen tests. `console.ts` imports this module
// before the app, because the app's API client (src/api.ts) takes `fetch` and
// `Request` when it is created at import time. Handlers are set in `console.ts`.
import { File as NodeFile } from 'node:buffer';
import { setupServer } from 'msw/node';
import { afterAll } from 'vitest';

export const server = setupServer();
server.listen({ onUnhandledFrame: 'error' });

// jsdom's FormData and File do not survive Node's fetch: the request body never
// ends (Vitest 5, jsdom 29). Screens build bodies with jsdom's classes, so fetch
// gets them as Node's classes here, the same bytes and file names. A Request built
// with a jsdom FormData keeps it aside until fetch converts it. jsdom's
// XMLHttpRequest needs none of this.
const JsdomFormData = window.FormData;
const BaseRequest = globalThis.Request;
const networkFetch = globalThis.fetch;
const NodeFormData = (await new Response(new URLSearchParams('a=1')).formData()).constructor as new () => FormData;
const heldForms = new WeakMap<Request, FormData>();

function isJsdomForm(body: unknown): body is FormData {
  return body instanceof JsdomFormData;
}

async function toNodeForm(form: FormData): Promise<FormData> {
  const out = new NodeFormData();
  for (const [key, value] of form.entries()) {
    if (typeof value === 'string') {
      out.append(key, value);
    } else {
      const bytes = new Uint8Array(await value.arrayBuffer());
      // No third argument: with a file name undici rewraps the file in the global (jsdom) File.
      out.append(key, new NodeFile([bytes], value.name, { type: value.type }) as unknown as Blob);
    }
  }
  return out;
}

class FormSafeRequest extends BaseRequest {
  constructor(request: RequestInfo | URL, init?: RequestInit) {
    // A browser resolves a relative URL against the page; Node's Request does not.
    const input = typeof request === 'string' ? new URL(request, window.location.href) : request;
    if (init?.body != null && isJsdomForm(init.body)) {
      const { body, ...rest } = init;
      super(input, rest);
      heldForms.set(this, body);
    } else {
      super(input, init);
    }
  }
}

async function formSafeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  let request = input;
  let options = init;
  if (options?.body != null && isJsdomForm(options.body)) {
    options = { ...options, body: await toNodeForm(options.body) };
  }
  if (request instanceof Request) {
    const held = heldForms.get(request);
    if (held) request = new BaseRequest(request, { body: await toNodeForm(held) });
  }
  return networkFetch(request, options);
}

globalThis.fetch = formSafeFetch;
globalThis.Request = FormSafeRequest;

// jsdom has no layout and no matchMedia. Answer media queries as a PC-width window.
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: (query: string) => ({
    matches: /min-width/.test(query),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

afterAll(() => {
  globalThis.fetch = networkFetch;
  globalThis.Request = BaseRequest;
  server.close();
});
