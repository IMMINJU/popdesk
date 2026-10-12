// One shape for every API answer, so screens handle the states of screens.md 2절
// the same way: 401, 403, 404, 429, 400 with or without fields, 409, 413, 5xx
// and a lost connection.

/** One entry of a 400 `fields` answer; the same in both contracts (`FieldError`). */
export interface FieldProblem {
  code: string;
  field: string;
}

export type Failure =
  | { kind: 'unauthenticated' }
  | { kind: 'forbidden'; code: string }
  | { kind: 'notFound' }
  | { kind: 'rateLimited'; seconds: number }
  /** 400 `INVALID_INPUT` with `fields`. */
  | { kind: 'invalid'; fields: FieldProblem[]; message: string }
  /** Any other 4xx with an error body: 400 without fields, 409. */
  | { kind: 'rejected'; status: number; code: string; message: string }
  | { kind: 'tooLarge' }
  | { kind: 'server' }
  | { kind: 'network' };

export type Result<T> = { ok: true; data: T; response: Response } | { ok: false; failure: Failure };

interface ApiAnswer<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

function errorBody(error: unknown): { code: string; message: string; fields?: FieldProblem[] } {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const body = error as { code: unknown; message?: unknown; fields?: unknown };
    return {
      code: typeof body.code === 'string' ? body.code : '',
      message: typeof body.message === 'string' ? body.message : '',
      ...(Array.isArray(body.fields) ? { fields: body.fields as FieldProblem[] } : {}),
    };
  }
  return { code: '', message: '' };
}

/** The error body is the app's own (it has a `code`), not the page of a proxy in front (S1A-AC-65). */
export function isAppError(error: unknown): boolean {
  return errorBody(error).code !== '';
}

/** Seconds from `Retry-After`: a number of seconds or an HTTP date. Default 60. */
export function retryAfter(response: Response): number {
  const header = response.headers.get('Retry-After');
  if (!header) return 60;
  const seconds = Number(header);
  if (Number.isFinite(seconds)) return Math.max(1, Math.ceil(seconds));
  const at = Date.parse(header);
  return Number.isNaN(at) ? 60 : Math.max(1, Math.ceil((at - Date.now()) / 1000));
}

export function failureOf(response: Response, error: unknown): Failure {
  const body = errorBody(error);
  const status = response.status;
  if (status === 401) return { kind: 'unauthenticated' };
  if (status === 403) return { kind: 'forbidden', code: body.code };
  if (status === 404) return { kind: 'notFound' };
  if (status === 413) return { kind: 'tooLarge' };
  if (status === 429) return { kind: 'rateLimited', seconds: retryAfter(response) };
  if (status >= 500) return { kind: 'server' };
  if (status === 400 && body.code === 'INVALID_INPUT' && body.fields && body.fields.length > 0) {
    return { kind: 'invalid', fields: body.fields, message: body.message };
  }
  if (body.code === '') return { kind: 'server' };
  return { kind: 'rejected', status, code: body.code, message: body.message };
}

/** Runs an API call and turns its answer, or a thrown network error, into a Result. */
export async function call<T>(request: Promise<ApiAnswer<T>>): Promise<Result<T>> {
  let answer: ApiAnswer<T>;
  try {
    answer = await request;
  } catch {
    return { ok: false, failure: { kind: 'network' } };
  }
  if (answer.response.ok) return { ok: true, data: answer.data as T, response: answer.response };
  return { ok: false, failure: failureOf(answer.response, answer.error) };
}
