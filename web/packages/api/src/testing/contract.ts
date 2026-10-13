// Checks a fake response of a web test against the contract file of its app
// (docs/integrate/openapi-{app}.json), so a fixture that drifts from the contract
// fails where it is made. The type check alone does not see when a field may be
// null, a closed value, or a field the contract does not have.
//
// A small reader of the JSON Schema words the two contract files use: `$ref`,
// `type` (a name or a list of names, which is how a nullable field is written),
// `enum`, `required`, `properties`, `additionalProperties`, `items`, `oneOf`,
// `format` (uuid, date-time, int32, int64) and `pattern`. An object schema without
// `additionalProperties` takes no field it does not name. `description` and
// `default` say nothing about a value. Any other word (`allOf`, `anyOf`, `const`,
// a length, a range) throws: a word this reader skipped would let a wrong fixture through.
import { readFileSync } from 'node:fs';
import { dirname, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';

export type ContractApp = 'portal' | 'console';

/** One schema node of a contract file. */
export interface Schema {
  $ref?: string;
  type?: string | readonly string[];
  enum?: readonly unknown[];
  required?: readonly string[];
  properties?: Readonly<Record<string, Schema>>;
  additionalProperties?: boolean | Schema;
  items?: Schema;
  oneOf?: readonly Schema[];
  format?: string;
  pattern?: string;
}

interface Operation {
  operationId?: string;
  responses?: Record<string, { content?: Record<string, { schema?: Schema }> }>;
}

/** The parts of a contract file this reader uses. */
export interface ContractDocument {
  paths?: Record<string, Record<string, Operation>>;
  components?: { schemas?: Record<string, Schema> };
}

/** The words `violations` checks, and the ones that say nothing about a value. */
const KNOWN_WORDS: ReadonlySet<string> = new Set([
  '$ref',
  'type',
  'enum',
  'required',
  'properties',
  'additionalProperties',
  'items',
  'oneOf',
  'format',
  'pattern',
  'description',
  'default',
]);

/** The words of one schema node this reader does not know, each in quotes. */
function unknownIn(schema: Schema): string[] {
  return Object.keys(schema)
    .filter((word) => !KNOWN_WORDS.has(word))
    .map((word) => `"${word}"`);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;
const INT32 = 2 ** 31;

function typeOf(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  return typeof value;
}

function shown(value: unknown): string {
  // `JSON.stringify` gives undefined for undefined and functions.
  const text = (JSON.stringify(value) as string | undefined) ?? String(value);
  return text.length > 60 ? `${text.slice(0, 57)}...` : text;
}

function resolve(doc: ContractDocument, ref: string): Schema | undefined {
  const name = /^#\/components\/schemas\/([^/]+)$/.exec(ref)?.[1];
  return name === undefined ? undefined : doc.components?.schemas?.[name];
}

function formatProblem(format: string, value: unknown): string | undefined {
  if (typeof value === 'string') {
    if (format === 'uuid' && !UUID.test(value)) return `${shown(value)} is not a uuid`;
    if (format === 'date-time' && (!DATE_TIME.test(value) || Number.isNaN(Date.parse(value)))) return `${shown(value)} is not a date-time`;
  }
  if (typeof value === 'number' && format === 'int32' && (value < -INT32 || value >= INT32)) return `${shown(value)} does not fit int32`;
  return undefined;
}

/**
 * Where `value` breaks `schema`, one line for each place: the path of the field
 * (`$.items[0].inline`) and why. Empty when it fits. Throws when the schema of a
 * field it reaches has a word this reader does not know.
 */
export function violations(doc: ContractDocument, schema: Schema, value: unknown, path = '$'): string[] {
  const unknown = unknownIn(schema);
  if (unknown.length > 0) throw new Error(`${path}: the contract check does not know the schema word ${unknown.join(', ')}`);
  if (schema.$ref !== undefined) {
    const target = resolve(doc, schema.$ref);
    return target ? violations(doc, target, value, path) : [`${path}: the contract has no ${schema.$ref}`];
  }
  if (schema.oneOf) {
    const tries = schema.oneOf.map((branch) => violations(doc, branch, value, path));
    const fits = tries.filter((found) => found.length === 0).length;
    if (fits === 1) return [];
    if (fits > 1) return [`${path}: ${shown(value)} fits more than one branch of oneOf`];
    // The branch that got furthest says the most: the one with the fewest problems that is not just "wrong type".
    const closest = [...tries].sort((a, b) => a.length - b.length).find((found) => found.some((line) => !line.startsWith(`${path}: expected `)));
    return closest ?? [`${path}: ${shown(value)} fits no branch of oneOf`];
  }

  const out: string[] = [];
  const actual = typeOf(value);
  if (schema.type !== undefined) {
    const allowed = typeof schema.type === 'string' ? [schema.type] : schema.type;
    const fits = allowed.includes(actual) || (actual === 'integer' && allowed.includes('number'));
    if (!fits) return [`${path}: expected ${allowed.join(' or ')}, got ${actual === 'undefined' ? 'nothing' : `${actual} ${shown(value)}`}`];
  }
  if (schema.enum && !schema.enum.includes(value)) {
    out.push(`${path}: ${shown(value)} is not one of ${schema.enum.map((v) => shown(v)).join(', ')}`);
  }
  if (schema.format !== undefined) {
    const problem = formatProblem(schema.format, value);
    if (problem !== undefined) out.push(`${path}: ${problem}`);
  }
  if (schema.pattern !== undefined && typeof value === 'string' && !new RegExp(schema.pattern, 'u').test(value)) {
    out.push(`${path}: ${shown(value)} does not match /${schema.pattern}/`);
  }

  if (actual === 'array' && schema.items) {
    const items = schema.items;
    (value as unknown[]).forEach((item, index) => {
      out.push(...violations(doc, items, item, `${path}[${String(index)}]`));
    });
  }

  if (actual === 'object' && (schema.properties || schema.required || schema.additionalProperties !== undefined || schema.type !== undefined)) {
    const fields = value as Record<string, unknown>;
    const known = schema.properties ?? {};
    for (const name of schema.required ?? []) {
      if (!(name in fields) || fields[name] === undefined) out.push(`${path}.${name}: required, but missing`);
    }
    for (const [name, field] of Object.entries(fields)) {
      if (field === undefined) continue;
      const own = Object.hasOwn(known, name) ? known[name] : undefined;
      if (own) out.push(...violations(doc, own, field, `${path}.${name}`));
      else if (schema.additionalProperties === undefined || schema.additionalProperties === false) out.push(`${path}.${name}: the contract has no such field`);
      else if (schema.additionalProperties !== true) out.push(...violations(doc, schema.additionalProperties, field, `${path}.${name}`));
    }
  }
  return out;
}

/**
 * Every schema word in `doc` this reader does not know, with where it is: in the
 * named schemas and in every `schema` under the paths (parameters, request bodies,
 * responses). Empty when the reader can check the whole file.
 */
export function unknownWords(doc: ContractDocument): string[] {
  const out: string[] = [];
  const inSchema = (schema: Schema, where: string) => {
    const unknown = unknownIn(schema);
    if (unknown.length > 0) out.push(`${where}: ${unknown.join(', ')}`);
    for (const [name, field] of Object.entries(schema.properties ?? {})) inSchema(field, `${where}.properties.${name}`);
    if (schema.items) inSchema(schema.items, `${where}.items`);
    if (typeof schema.additionalProperties === 'object') inSchema(schema.additionalProperties, `${where}.additionalProperties`);
    (schema.oneOf ?? []).forEach((branch, index) => {
      inSchema(branch, `${where}.oneOf[${String(index)}]`);
    });
  };
  const inPaths = (node: unknown, where: string) => {
    if (Array.isArray(node)) {
      node.forEach((item, index) => {
        inPaths(item, `${where}[${String(index)}]`);
      });
    } else if (typeof node === 'object' && node !== null) {
      for (const [key, value] of Object.entries(node)) {
        if (key === 'schema') inSchema(value as Schema, `${where}.schema`);
        else inPaths(value, `${where}.${key}`);
      }
    }
  };
  for (const [name, schema] of Object.entries(doc.components?.schemas ?? {})) inSchema(schema, `#/components/schemas/${name}`);
  inPaths(doc.paths ?? {}, '#/paths');
  return out;
}

/** The JSON schema of the `status` response of `operationId`, or why there is none. */
export function responseSchema(doc: ContractDocument, operationId: string, status: number): Schema | string {
  for (const item of Object.values(doc.paths ?? {})) {
    for (const operation of Object.values(item)) {
      if (operation.operationId !== operationId) continue;
      const response = operation.responses?.[String(status)];
      if (!response) return `the contract gives ${operationId} no ${String(status)} response (it has ${Object.keys(operation.responses ?? {}).join(', ')})`;
      const schema = response.content?.['application/json']?.schema;
      return schema ?? `the ${String(status)} response of ${operationId} has no JSON body in the contract`;
    }
  }
  return `the contract has no operation ${operationId}`;
}

/** Throws with every place `value` breaks the `status` response of `operationId` in `doc`; `source` names the file. */
export function checkAgainst<T>(doc: ContractDocument, source: string, operationId: string, status: number, value: T): T {
  const schema = responseSchema(doc, operationId, status);
  if (typeof schema === 'string') throw new Error(`${source}: ${schema}`);
  const found = violations(doc, schema, value);
  if (found.length > 0) {
    throw new Error(`A fake ${operationId} ${String(status)} response does not match ${source}:\n${found.map((line) => `  ${line}`).join('\n')}`);
  }
  return value;
}

const documents = new Map<ContractApp, ContractDocument>();

/** The contract file of `app`, read once. */
export function contractDocument(app: ContractApp): ContractDocument {
  let doc = documents.get(app);
  if (!doc) {
    // A path, not `new URL(…, import.meta.url)`: Vite takes that form for an asset of the app and refuses a file outside `web/`.
    const file = resolvePath(dirname(fileURLToPath(import.meta.url)), '../../../../../docs/integrate', `openapi-${app}.json`);
    doc = JSON.parse(readFileSync(file, 'utf8')) as ContractDocument;
    documents.set(app, doc);
  }
  return doc;
}

/**
 * `value` itself, after checking it against the JSON body the contract of `app`
 * gives the `status` response of `operationId`. Throws, naming each field that is
 * wrong and why, when it does not fit.
 *
 *     HttpResponse.json(checkResponse('portal', 'uploadImage', 201, { id }), { status: 201 })
 */
export function checkResponse<T>(app: ContractApp, operationId: string, status: number, value: T): T {
  return checkAgainst(contractDocument(app), `openapi-${app}.json`, operationId, status, value);
}
