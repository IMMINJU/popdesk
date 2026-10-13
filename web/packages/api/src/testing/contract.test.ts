// 웹 테스트의 가짜 응답을 계약 스키마로 검증하는 도움(5번 묶음 acceptance.md 4절).
// 검증기는 직접 쓴 것이라 그 낱말마다 본다: $ref, nullable(type의 배열), enum, required,
// additionalProperties가 없는 객체의 모르는 칸, items, oneOf, format, pattern.
import { describe, expect, it, test } from 'vitest';
import { checkAgainst, checkResponse, responseSchema, violations, type ContractDocument, type Schema } from './contract';

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
});
