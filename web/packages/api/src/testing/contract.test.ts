// 웹 테스트의 가짜 응답을 계약 스키마로 검증하는 도움(5번 묶음 acceptance.md 4절).
// 검증기는 직접 쓴 것이라 그 낱말마다 본다: $ref, nullable(type의 배열), enum, required,
// additionalProperties가 없는 객체의 모르는 칸, items, oneOf, format, pattern. 모르는 낱말은 던진다.
// 끝의 묶음은 5번 묶음 웹 테스트의 견본(portal5.ts, console5.ts)과 같은 값을 진짜 계약 파일로 본다.
import { describe, expect, it, test } from 'vitest';
import { checkAgainst, checkResponse, contractDocument, responseSchema, unknownWords, violations, type ContractDocument, type Schema } from './contract';

const DOC: ContractDocument = {
  paths: {
    '/things': {
      get: {
        operationId: 'listThings',
        responses: {
          '200': { content: { 'application/json': { schema: { $ref: '#/components/schemas/PagedThing' } } } },
          '204': {},
          '206': { content: { 'application/octet-stream': { schema: { type: 'string' } } } },
        },
      },
    },
  },
  components: {
    schemas: {
      PagedThing: {
        type: 'object',
        required: ['items', 'page'],
        properties: { items: { type: 'array', items: { $ref: '#/components/schemas/Thing' } }, page: { type: 'integer', format: 'int32' } },
      },
      Thing: {
        type: 'object',
        required: ['id', 'kind', 'name', 'owner', 'createdAt'],
        properties: {
          id: { type: 'string', format: 'uuid' },
          kind: { type: 'string', enum: ['FILE', 'IMAGE'] },
          name: { type: ['string', 'null'] },
          owner: { oneOf: [{ $ref: '#/components/schemas/Owner' }, { type: 'null' }] },
          createdAt: { type: 'string', format: 'date-time' },
          code: { type: 'string', pattern: '^[A-Z]{2,4}$' },
          note: { type: 'string' },
          labels: { type: 'object', additionalProperties: { type: 'string' } },
        },
      },
      Owner: { type: 'object', required: ['accountId'], properties: { accountId: { type: ['integer', 'null'], format: 'int64' } } },
      Broken: { $ref: '#/components/schemas/Missing' },
    },
  },
};

const THING: Schema = { $ref: '#/components/schemas/Thing' };

function thing(over: Record<string, unknown> = {}): Record<string, unknown> {
  return { id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01', kind: 'FILE', name: 'log.txt', owner: { accountId: 3 }, createdAt: '2026-10-08T03:15:00Z', ...over };
}

describe('계약 검증기 violations', () => {
  it.each<[string, Record<string, unknown>]>([
    ['필수 칸만 있다', thing()],
    ['nullable 칸이 null이다', thing({ name: null })],
    ['oneOf의 null 갈래', thing({ owner: null })],
    ['$ref 안의 nullable 칸이 null이다', thing({ owner: { accountId: null } })],
    ['필수가 아닌 칸이 있다', thing({ note: '메모', code: 'ACME' })],
    ['필수가 아닌 칸이 undefined다(JSON에 실리지 않는다)', thing({ note: undefined })],
    ['additionalProperties가 스키마인 객체의 아무 칸', thing({ labels: { a: 'x', b: 'y' } })],
    ['소수 초와 시간대가 든 date-time', thing({ createdAt: '2026-10-08T03:15:00.123+09:00' })],
    ['대문자 uuid', thing({ id: '7F1C2A9E-0C4B-4D8E-9A51-3B2F6C1D0E01' })],
  ])('맞는 값은 걸리지 않는다: %s', (_name, value) => {
    expect(violations(DOC, THING, value)).toEqual([]);
  });

  it.each<[string, unknown, string[]]>([
    ['필수 칸이 없다', { id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01', kind: 'FILE', owner: null, createdAt: '2026-10-08T03:15:00Z' }, ['$.name: required, but missing']],
    ['필수 칸이 undefined다', thing({ name: undefined }), ['$.name: required, but missing']],
    ['nullable이 아닌 칸이 null이다', thing({ kind: null }), ['$.kind: expected string, got null null']],
    ['type이 다르다', thing({ name: 7 }), ['$.name: expected string or null, got integer 7']],
    ['닫힌 값(enum)에 없다', thing({ kind: 'VIDEO' }), ['$.kind: "VIDEO" is not one of "FILE", "IMAGE"']],
    ['계약에 없는 칸이 있다', thing({ inline: false }), ['$.inline: the contract has no such field']],
    ['uuid 꼴이 아니다', thing({ id: 'image-1' }), ['$.id: "image-1" is not a uuid']],
    ['date-time 꼴이 아니다', thing({ createdAt: '2026-10-08' }), ['$.createdAt: "2026-10-08" is not a date-time']],
    ['없는 날의 date-time', thing({ createdAt: '2026-13-45T03:15:00Z' }), ['$.createdAt: "2026-13-45T03:15:00Z" is not a date-time']],
    ['pattern에 안 맞는다', thing({ code: 'acme' }), ['$.code: "acme" does not match /^[A-Z]{2,4}$/']],
    ['oneOf의 어느 갈래도 아니다(다른 type)', thing({ owner: 'kim' }), ['$.owner: "kim" fits no branch of oneOf']],
    ['oneOf 갈래 안의 칸이 틀렸다', thing({ owner: { accountId: 'x' } }), ['$.owner.accountId: expected integer or null, got string "x"']],
    ['oneOf 갈래 안에 모르는 칸', thing({ owner: { accountId: 1, name: '김' } }), ['$.owner.name: the contract has no such field']],
    ['additionalProperties의 값이 틀렸다', thing({ labels: { a: 1 } }), ['$.labels.a: expected string, got integer 1']],
    ['객체가 아니다', ['log.txt'], ['$: expected object, got array ["log.txt"]']],
    ['긴 값은 줄여 보인다', 'x'.repeat(80), [`$: expected object, got string "${'x'.repeat(56)}...`]],
    ['정수 칸에 소수', { accountId: 1.5 }, ['$.accountId: expected integer or null, got number 1.5']],
  ])('틀린 값은 칸과 까닭을 말한다: %s', (name, value, wanted) => {
    const schema: Schema = name === '정수 칸에 소수' ? { $ref: '#/components/schemas/Owner' } : THING;
    expect(violations(DOC, schema, value)).toEqual(wanted);
  });

  test('여러 칸이 틀리면 모두 말하고, 배열은 몇째인지 말한다', () => {
    const page = { items: [thing(), thing({ kind: 'VIDEO', extra: 1 })], page: 2 ** 31 };

    expect(violations(DOC, { $ref: '#/components/schemas/PagedThing' }, page)).toEqual([
      '$.items[1].kind: "VIDEO" is not one of "FILE", "IMAGE"',
      '$.items[1].extra: the contract has no such field',
      '$.page: 2147483648 does not fit int32',
    ]);
  });

  test('oneOf의 두 갈래에 다 맞으면 말한다', () => {
    const schema: Schema = { oneOf: [{ type: 'string' }, { type: ['string', 'null'] }] };

    expect(violations(DOC, schema, 'x')).toEqual(['$: "x" fits more than one branch of oneOf']);
    expect(violations(DOC, schema, null)).toEqual([]);
  });

  test('없는 $ref는 조용히 지나가지 않는다', () => {
    expect(violations(DOC, { $ref: '#/components/schemas/Broken' }, {})).toEqual(['$: the contract has no #/components/schemas/Missing']);
  });

  test('additionalProperties가 true면 모르는 칸을 받고, false면 받지 않는다', () => {
    expect(violations(DOC, { type: 'object', additionalProperties: true }, { any: 1 })).toEqual([]);
    expect(violations(DOC, { type: 'object', additionalProperties: false }, { any: 1 })).toEqual(['$.any: the contract has no such field']);
  });

  it.each<[string, Record<string, unknown>, unknown]>([
    ['allOf', { allOf: [{ type: 'string' }] }, 'x'],
    ['anyOf', { anyOf: [{ type: 'string' }, { type: 'null' }] }, 'x'],
    ['const', { type: 'string', const: 'FILE' }, 'FILE'],
    ['not', { not: { type: 'null' } }, 'x'],
    ['minLength', { type: 'string', minLength: 1 }, 'x'],
    ['maxLength', { type: 'string', maxLength: 255 }, 'x'],
    ['minimum', { type: 'integer', minimum: 1 }, 3],
    ['maximum', { type: 'integer', maximum: 100 }, 3],
    ['minItems', { type: 'array', minItems: 1 }, ['x']],
    ['maxItems', { type: 'array', maxItems: 10 }, ['x']],
    ['uniqueItems', { type: 'array', uniqueItems: true }, ['x']],
    ['nullable', { type: 'string', nullable: true }, 'x'],
  ])('모르는 낱말을 만나면 맞는 값이어도 던진다: %s', (word, schema, value) => {
    expect(() => violations(DOC, schema as Schema, value)).toThrow(`$: the contract check does not know the schema word "${word}"`);
  });

  test('모르는 낱말은 닿은 칸의 자리와 함께 모두 말하고, 값에 대해 말하지 않는 낱말(description, default)은 지난다', () => {
    const schema = {
      type: 'object',
      description: '설명',
      properties: {
        name: { type: 'string', description: '이름', default: 'x' },
        files: { type: 'array', items: { type: 'string', minLength: 1, maxLength: 255 } },
      },
    } as Schema;

    expect(violations(DOC, schema, { name: 'log.txt' })).toEqual([]);
    expect(violations(DOC, schema, { name: 'log.txt', files: [] }), '닿지 않은 스키마는 보지 않는다').toEqual([]);
    expect(() => violations(DOC, schema, { files: ['a'] })).toThrow('$.files[0]: the contract check does not know the schema word "minLength", "maxLength"');
  });

  test('unknownWords: 계약 파일의 이름 붙은 스키마와 경로 아래의 모든 schema에서 모르는 낱말을 자리와 함께 찾는다', () => {
    const doc = {
      paths: {
        '/things': {
          get: {
            operationId: 'listThings',
            parameters: [{ name: 'size', in: 'query', schema: { type: 'integer', format: 'int32', default: 20, maximum: 100 } }],
            requestBody: { content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/Thing' }] } } } },
            responses: { '200': { content: { 'application/json': { schema: { $ref: '#/components/schemas/Thing' } } } } },
          },
        },
      },
      components: {
        schemas: {
          Thing: {
            type: 'object',
            properties: {
              name: { type: 'string', maxLength: 255 },
              tags: { type: 'array', items: { type: 'string', minLength: 1 } },
              owner: { oneOf: [{ type: 'null' }, { type: 'string', const: 'kim' }] },
              labels: { type: 'object', additionalProperties: { type: 'string', pattern: '^a', anyOf: [] } },
            },
          },
        },
      },
    } as unknown as ContractDocument;

    expect(unknownWords(doc)).toEqual([
      '#/components/schemas/Thing.properties.name: "maxLength"',
      '#/components/schemas/Thing.properties.tags.items: "minLength"',
      '#/components/schemas/Thing.properties.owner.oneOf[1]: "const"',
      '#/components/schemas/Thing.properties.labels.additionalProperties: "anyOf"',
      '#/paths./things.get.parameters[0].schema: "maximum"',
      '#/paths./things.get.requestBody.content.application/json.schema: "allOf"',
    ]);
    expect(unknownWords(DOC)).toEqual([]);
  });
});

describe('계약 검증기 응답 찾기', () => {
  test('operationId와 상태 코드로 JSON 응답의 스키마를 찾는다', () => {
    expect(responseSchema(DOC, 'listThings', 200)).toEqual({ $ref: '#/components/schemas/PagedThing' });
  });

  it.each<[string, string, number, string]>([
    ['없는 operation', 'dropThings', 200, 'the contract has no operation dropThings'],
    ['없는 상태 코드', 'listThings', 201, 'the contract gives listThings no 201 response (it has 200, 204, 206)'],
    ['본문이 없는 응답', 'listThings', 204, 'the 204 response of listThings has no JSON body in the contract'],
    ['JSON이 아닌 응답', 'listThings', 206, 'the 206 response of listThings has no JSON body in the contract'],
  ])('찾지 못하면 까닭을 말한다: %s', (_name, operationId, status, wanted) => {
    expect(responseSchema(DOC, operationId, status)).toBe(wanted);
    expect(() => checkAgainst(DOC, 'test.json', operationId, status, {})).toThrow(`test.json: ${wanted}`);
  });

  test('맞으면 받은 값을 그대로 돌려주고, 틀리면 operation, 상태 코드, 파일, 칸마다의 까닭을 담아 던진다', () => {
    const good = { items: [thing()], page: 1 };
    expect(checkAgainst(DOC, 'test.json', 'listThings', 200, good)).toBe(good);

    expect(() => checkAgainst(DOC, 'test.json', 'listThings', 200, { items: [thing({ name: 1 })] })).toThrow(
      'A fake listThings 200 response does not match test.json:\n  $.page: required, but missing\n  $.items[0].name: expected string or null, got integer 1',
    );
  });
});

describe('계약 검증기 checkResponse: 진짜 계약 파일', () => {
  const ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e05';

  it.each(['portal', 'console'] as const)('%s: 임시 이미지 응답(uploadImage 201)은 id 하나다', (app) => {
    expect(checkResponse(app, 'uploadImage', 201, { id: ID })).toEqual({ id: ID });
    expect(() => checkResponse(app, 'uploadImage', 201, { id: ID, fileName: 'shot.png' })).toThrow(
      `A fake uploadImage 201 response does not match openapi-${app}.json:\n  $.fileName: the contract has no such field`,
    );
    expect(() => checkResponse(app, 'uploadImage', 201, { id: 'shot' })).toThrow('$.id: "shot" is not a uuid');
  });

  it.each(['portal', 'console'] as const)('%s: 오류 응답(uploadImage 400)은 ApiError다', (app) => {
    const body = { code: 'FILE_NOT_IMAGE', message: 'Not an image.' };
    expect(checkResponse(app, 'uploadImage', 400, body)).toBe(body);
    expect(() => checkResponse(app, 'uploadImage', 400, { message: 'Not an image.' })).toThrow('$.code: required, but missing');
  });

  test('앱마다 자기 계약을 읽는다: 콘솔에만 있는 operation은 포털에서 못 찾는다', () => {
    expect(() => checkResponse('portal', 'listIssueHistory', 200, {})).toThrow('openapi-portal.json: the contract has no operation listIssueHistory');
    expect(() => checkResponse('console', 'listIssueHistory', 200, {})).toThrow('A fake listIssueHistory 200 response does not match openapi-console.json');
  });

  it.each(['portal', 'console'] as const)('%s: 계약 파일 전체에 검증기가 모르는 스키마 낱말이 없다', (app) => {
    expect(unknownWords(contractDocument(app))).toEqual([]);
  });
});

// The values the slice 5 screen tests give their fake servers (portal5.ts and console5.ts over
// portal.ts, console.ts and console3.ts), written out here: those files start a fake server
// and belong to the apps, so this package does not import them.
describe('계약 검증기 checkResponse: 5번 묶음 웹 테스트의 견본', () => {
  const T0 = '2026-10-08T03:15:00Z';
  const OLD_IMAGE = '5a000000-0000-4000-8000-000000000001';
  const PEOPLE = {
    portal: { accountId: 11, kind: 'CUSTOMER', name: '김고객', photoUpdatedAt: null },
    console: { accountId: 11, name: '김고객', role: 'CUSTOMER', photoUpdatedAt: null },
  };
  const AGENT_PERSON = { accountId: 2, name: '이상담', role: 'AGENT', photoUpdatedAt: null };

  /** `attachment()` of the app's support file. */
  function attachment(app: 'portal' | 'console', over: Record<string, unknown> = {}): Record<string, unknown> {
    return {
      id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01',
      commentId: null,
      contentType: 'text/plain',
      createdAt: T0,
      deleted: false,
      deletedAt: null,
      deletedBy: null,
      fileName: 'log.txt',
      inline: false,
      ...(app === 'console' ? { internal: false } : {}),
      size: 2048,
      uploadedBy: PEOPLE[app],
      ...over,
    };
  }

  /** `inlineAttachment()` of portal5.ts and console5.ts. */
  const inlineAttachment = (app: 'portal' | 'console', over: Record<string, unknown> = {}) =>
    attachment(app, { id: OLD_IMAGE, fileName: 'old.png', contentType: 'image/png', inline: true, ...over });
  /** A deleted content image: `deletedAttachment()` of console5.ts, and what the DELETE handler of portal5.ts leaves. */
  const deletedImage = (app: 'portal' | 'console') => inlineAttachment(app, { deleted: true, fileName: null, deletedAt: T0, deletedBy: PEOPLE[app] });
  /** `paged()` of the app's support file. */
  const paged = (items: unknown[]) => ({ items, page: 1, size: 20, totalElements: items.length, totalPages: 1 });
  /** `rejected()` and `imageRejected()` of the support files. */
  const rejected = (code: string) => ({ code, message: `${code} (server text).` });

  it.each(['portal', 'console'] as const)('%s: 첨부 목록(listAttachments 200)의 본문 이미지(inline이 참), 댓글의 본문 이미지, 지워진 흔적 견본이 계약에 맞는다', (app) => {
    const page = paged([inlineAttachment(app), inlineAttachment(app, { commentId: 5 }), deletedImage(app), attachment(app)]);

    expect(checkResponse(app, 'listAttachments', 200, page)).toBe(page);
    const before5 = Object.fromEntries(Object.entries(inlineAttachment(app)).filter(([name]) => name !== 'inline'));
    expect(() => checkResponse(app, 'listAttachments', 200, paged([before5])), 'inline이 없는 옛 견본').toThrow('$.items[0].inline: required, but missing');
    expect(() => checkResponse(app, 'listAttachments', 200, paged([inlineAttachment(app, { inline: null })]))).toThrow('$.items[0].inline: expected boolean, got null null');
  });

  test('portal: 첨부에는 internal 칸이 없고, console: 내부 댓글의 본문 이미지는 internal이 참이다', () => {
    expect(() => checkResponse('portal', 'listAttachments', 200, paged([inlineAttachment('portal', { internal: false })]))).toThrow(
      '$.items[0].internal: the contract has no such field',
    );
    const page = paged([inlineAttachment('console', { commentId: 32, internal: true, uploadedBy: AGENT_PERSON })]);
    expect(checkResponse('console', 'listAttachments', 200, page)).toBe(page);
  });

  test('console: 이력(listIssueHistory 200)의 ATTACHMENT 견본이 계약에 맞는다. 붙임은 newValue에, 지움은 oldValue에 첨부 ID가 든다', () => {
    const entry = (over: Record<string, unknown>) => ({
      id: 1,
      kind: 'CHANGED',
      field: 'ATTACHMENT',
      oldValue: null,
      newValue: null,
      oldPerson: null,
      newPerson: null,
      oldLink: null,
      newLink: null,
      actor: AGENT_PERSON,
      createdAt: '2026-10-09T01:02:03Z',
      ...over,
    });
    const page = paged([entry({ newValue: OLD_IMAGE }), entry({ oldValue: OLD_IMAGE }), entry({ newValue: OLD_IMAGE, actor: PEOPLE.console })]);

    expect(checkResponse('console', 'listIssueHistory', 200, page)).toBe(page);
    expect(() => checkResponse('console', 'listIssueHistory', 200, paged([entry({ newValue: OLD_IMAGE, fileName: 'old.png' })]))).toThrow(
      '$.items[0].fileName: the contract has no such field',
    );
  });

  it.each(['FILE_NOT_IMAGE', 'FILE_TOO_LARGE', 'FILE_EMPTY', 'TEMPORARY_IMAGE_LIMIT_EXCEEDED', 'ATTACHMENT_QUOTA_EXCEEDED'])(
    'console: 올리기의 400 견본(%s)이 계약에 맞는다',
    (code) => {
      const body = rejected(code);
      expect(checkResponse('console', 'uploadImage', 400, body)).toBe(body);
    },
  );

  it.each(
    (['portal', 'console'] as const).flatMap((app) => ['createIssue', 'updateIssue', 'createComment', 'updateComment'].map((operationId) => ({ app, operationId }))),
  )('$app: 저장($operationId)의 400 TEMPORARY_IMAGE_INVALID 견본이 계약에 맞는다', ({ app, operationId }) => {
    const body = rejected('TEMPORARY_IMAGE_INVALID');
    expect(checkResponse(app, operationId, 400, body)).toBe(body);
  });
});

describe('계약 검증기 checkResponse: 7a 묶음 웹 테스트의 견본', () => {
  const ADMIN_PERSON = { accountId: 1, name: '최관리', role: 'ADMIN', photoUpdatedAt: null };
  const paged = (items: unknown[]) => ({ items, page: 1, size: 20, totalElements: items.length, totalPages: 1 });

  /** `trashed()` of console7a.ts. */
  const trashed = (over: Record<string, unknown> = {}) => ({
    key: 'ACME-12',
    title: '결제 내역이 두 번 보입니다',
    companyCode: 'ACME',
    companyName: '에이크미',
    status: 'IN_PROGRESS',
    deletedAt: '2026-10-07T03:20:00.000Z',
    deletedBy: ADMIN_PERSON,
    purgeAt: '2026-11-06T03:20:00.000Z',
    ...over,
  });

  test('console: 휴지통 목록(listTrash 200)의 TrashedIssue 견본이 계약에 맞고, 칸은 모두 필수다', () => {
    const page = paged([trashed(), trashed({ key: 'BETA-5', companyCode: 'BETA', companyName: '베타상사', status: 'RESOLVED' })]);

    expect(checkResponse('console', 'listTrash', 200, page)).toBe(page);
    const withoutPurgeAt = Object.fromEntries(Object.entries(trashed()).filter(([name]) => name !== 'purgeAt'));
    expect(() => checkResponse('console', 'listTrash', 200, paged([withoutPurgeAt]))).toThrow('$.items[0].purgeAt: required, but missing');
    expect(() => checkResponse('console', 'listTrash', 200, paged([trashed({ deletedBy: null })]))).toThrow('$.items[0].deletedBy');
  });

  test('console: 복구의 409 RESTORE_LINK_CONFLICT 견본(issueKeys)과 404 견본이 계약에 맞는다', () => {
    const conflict = { code: 'RESTORE_LINK_CONFLICT', message: 'RESTORE_LINK_CONFLICT (server text).', issueKeys: ['ACME-40', 'BETA-77'] };
    const missing = { code: 'NOT_FOUND', message: 'NOT_FOUND (server text).' };

    expect(checkResponse('console', 'restoreIssue', 409, conflict)).toBe(conflict);
    expect(checkResponse('console', 'restoreIssue', 404, missing)).toBe(missing);
    expect(checkResponse('console', 'purgeIssue', 404, missing)).toBe(missing);
    expect(() => checkResponse('console', 'restoreIssue', 409, { ...conflict, issueKeys: 'ACME-40' })).toThrow('$.issueKeys');
  });

  test('console: 이력(listIssueHistory 200)의 TRASH 견본(지우기, 복구, 영구 삭제)이 계약에 맞는다', () => {
    const entry = (over: Record<string, unknown>) => ({
      id: 1,
      kind: 'CHANGED',
      field: 'TRASH',
      oldValue: null,
      newValue: null,
      oldPerson: null,
      newPerson: null,
      oldLink: null,
      newLink: null,
      actor: ADMIN_PERSON,
      createdAt: '2026-10-08T03:15:00Z',
      ...over,
    });
    const page = paged([entry({ newValue: 'TRASHED' }), entry({ oldValue: 'TRASHED' }), entry({ oldValue: 'TRASHED', newValue: 'PURGED' })]);

    expect(checkResponse('console', 'listIssueHistory', 200, page)).toBe(page);
  });

  test('console: 이력(listIssueHistory 200)의 시스템 행위자(자동 종료, 영구 삭제) 견본이 계약에 맞는다. role은 null이다', () => {
    const SYSTEM = { accountId: 0, name: null, role: null, photoUpdatedAt: null };
    const entry = (over: Record<string, unknown>) => ({
      id: 1,
      kind: 'CHANGED',
      field: 'STATUS',
      oldValue: 'RESOLVED',
      newValue: 'CLOSED',
      oldPerson: null,
      newPerson: null,
      oldLink: null,
      newLink: null,
      actor: SYSTEM,
      createdAt: '2026-10-15T03:10:00Z',
      ...over,
    });
    const page = paged([entry({}), entry({ field: 'TRASH', oldValue: 'TRASHED', newValue: 'PURGED' })]);

    expect(checkResponse('console', 'listIssueHistory', 200, page)).toBe(page);
    expect(() => checkResponse('console', 'listIssueHistory', 200, paged([entry({ actor: { ...SYSTEM, role: 'SYSTEM' } })]))).toThrow(
      '$.items[0].actor.role',
    );
  });

  test('console: 알림함(listNotifications 200)의 actor가 null인 문의 항목(자동 종료) 견본이 계약에 맞는다', () => {
    const item = (over: Record<string, unknown>) => ({
      id: 703,
      kind: 'ISSUE_CLOSED',
      actor: null,
      issue: { key: 'ACME-7', title: '로그인이 안 됩니다' },
      commentId: null,
      companyCode: null,
      task: null,
      linkedIssue: null,
      readAt: null,
      createdAt: '2026-10-08T03:15:00Z',
      ...over,
    });
    const page = paged([item({}), item({ id: 704, kind: 'LINKED_ISSUE_CLOSED', linkedIssue: { key: 'BETA-3', title: '결제가 두 번 됩니다' } })]);

    expect(checkResponse('console', 'listNotifications', 200, page)).toBe(page);
  });
});

describe('계약 검증기 checkResponse: 7b 묶음 웹 테스트의 견본', () => {
  /** `stat()` of console7b.ts. */
  const stat = (over: Record<string, unknown> = {}) => ({
    companyCode: 'ACME',
    companyName: '에이크미',
    companyStatus: 'ACTIVE',
    total: 12,
    open: 5,
    inProgress: 3,
    resolved: 0,
    closed: 4,
    ...over,
  });

  test('console: 통계(getCompanyStats 200)의 견본(보관한 고객사, 큰 수, 빈 목록)이 계약에 맞고, 칸은 모두 필수다', () => {
    const stats = {
      items: [stat(), stat({ companyCode: 'OLD', companyName: '옛상사', companyStatus: 'ARCHIVED', total: 123456, open: 0, inProgress: 0, resolved: 0, closed: 123456 })],
    };
    const empty = { items: [] };

    expect(checkResponse('console', 'getCompanyStats', 200, stats)).toBe(stats);
    expect(checkResponse('console', 'getCompanyStats', 200, empty)).toBe(empty);
    const withoutOpen = Object.fromEntries(Object.entries(stat()).filter(([name]) => name !== 'open'));
    expect(() => checkResponse('console', 'getCompanyStats', 200, { items: [withoutOpen] })).toThrow('$.items[0].open: required, but missing');
    expect(() => checkResponse('console', 'getCompanyStats', 200, { items: [stat({ companyStatus: 'DELETED' })] })).toThrow('$.items[0].companyStatus');
  });

  test('console: 알림함(listNotifications 200)의 CLEANUP_STALLED 견본이 계약에 맞는다. 문의가 없는 운영 경보다', () => {
    const alert = {
      id: 821,
      kind: 'CLEANUP_STALLED',
      actor: null,
      issue: null,
      commentId: null,
      companyCode: null,
      task: null,
      linkedIssue: null,
      readAt: null,
      createdAt: '2026-10-08T03:15:00Z',
    };
    const page = { items: [alert], page: 1, size: 20, totalElements: 1, totalPages: 1 };

    expect(checkResponse('console', 'listNotifications', 200, page)).toBe(page);
  });
});
