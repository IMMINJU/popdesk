// screens.md 2절 오류 처리와 C-4 첨부(ATT-2). C-10 변이 테스트가 찾은 빈칸: 칸 오류 code마다의 문구, 실패 종류마다의 띠 문구,
// 첨부 실패 문구, 공개 첨부 합, 응답 분류(failureOf, Retry-After), 쪽 주소. 문구 글자는 screens.md에 없어 고정하지 않고,
// 서로 다른지·한도 숫자가 들었는지·같은 이유면 같은 문구인지를 본다.
import { describe, expect, test } from 'vitest';
import type { Schemas } from '../api';
import { ATTACHMENT_LIMITS, publicBytes, REJECT_TEXT, uploadFailureText } from './attachments';
import { failureText, fieldText, pageAnnouncement, rateLimitedText, TEXT } from './messages';
import { pageHref } from './paging';
import { failureOf, retryAfter, type Failure } from './result';

type Attachment = Schemas['Attachment'];

const CODES = ['REQUIRED', 'BLANK', 'TOO_SHORT', 'TOO_LONG', 'INVALID_FORMAT', 'UNKNOWN_VALUE', 'SAME_AS_EMAIL', 'NOT_ALLOWED'];

function attachment(over: Partial<Attachment>): Attachment {
  return {
    commentId: null,
    contentType: 'text/plain',
    createdAt: '2026-10-08T03:15:00Z',
    deleted: false,
    deletedAt: null,
    deletedBy: null,
    fileName: 'log.txt',
    id: 'a1',
    inline: false,
    internal: false,
    size: 100,
    uploadedBy: { accountId: 1, name: '김상담', role: 'AGENT', photoUpdatedAt: null },
    ...over,
  };
}

const answer = (status: number, headers: Record<string, string> = {}) => new Response(null, { status, headers });

describe('C-4 fieldText', () => {
  test('C-4 fieldText: 알려진 code마다 서로 다른 문구, 모르는 code는 그 모두와 다른 기본 문구', () => {
    const texts = CODES.map((c) => fieldText(c));
    expect(texts.every((t) => t.length > 0)).toBe(true);
    expect(new Set(texts).size).toBe(CODES.length);
    expect(texts).not.toContain(fieldText('SOMETHING_NEW'));
    expect(fieldText('SOMETHING_NEW').length).toBeGreaterThan(0);
  });

  test('C-4 fieldText: 한도를 주면 TOO_SHORT·TOO_LONG·INVALID_FORMAT 문구에 그 값이 들어간다', () => {
    expect(fieldText('TOO_SHORT', { min: 12 })).toContain('12');
    expect(fieldText('TOO_SHORT')).not.toBe(fieldText('TOO_SHORT', { min: 12 }));
    expect(fieldText('TOO_LONG', { max: 20000 })).toContain('20,000');
    expect(fieldText('TOO_LONG')).not.toBe(fieldText('TOO_LONG', { max: 20000 }));
    expect(fieldText('INVALID_FORMAT', { format: '이메일' })).toContain('이메일');
    expect(fieldText('INVALID_FORMAT')).not.toBe(fieldText('INVALID_FORMAT', { format: '이메일' }));
    expect([fieldText('TOO_SHORT'), fieldText('TOO_LONG'), fieldText('INVALID_FORMAT')].join(' ')).not.toContain('undefined');
  });
});

describe('C-4 failureText', () => {
  const kinds: Failure[] = [
    { kind: 'unauthenticated' },
    { kind: 'forbidden', code: 'FORBIDDEN' },
    { kind: 'notFound' },
    { kind: 'rateLimited', seconds: 7 },
    { kind: 'tooLarge' },
    { kind: 'server' },
  ];

  test('C-4 failureText: 실패 종류마다 화면 문구(TEXT)를 쓰고, 연결 끊김은 서버 오류와 같다', () => {
    expect(kinds.map((f) => failureText(f))).toEqual([
      TEXT.saveSessionEnded,
      TEXT.forbidden,
      TEXT.notFound,
      rateLimitedText(7),
      TEXT.tooLarge,
      TEXT.server,
    ]);
    expect(failureText({ kind: 'network' })).toBe(TEXT.server);
    expect(rateLimitedText(7)).toContain('7');
  });

  test('C-4 failureText: 화면이 아는 code는 화면 문구, 모르는 code와 칸 오류는 서버 message', () => {
    const known = { ISSUE_CLOSED: '종료된 문의입니다', NOT_FOUND: '이 문의는 없습니다' };
    expect(failureText({ kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: 'closed' }, known)).toBe('종료된 문의입니다');
    expect(failureText({ kind: 'rejected', status: 409, code: 'OTHER', message: '서버 문구' }, known)).toBe('서버 문구');
    expect(failureText({ kind: 'invalid', fields: [], message: '칸 오류' }, known)).toBe('칸 오류');
    expect(failureText({ kind: 'notFound' }, known)).toBe('이 문의는 없습니다');
  });

  test('C-3 pageAnnouncement: "2쪽, 45건 가운데 21~40"처럼 쪽과 범위', () => {
    expect(pageAnnouncement(2, 20, 45, 20)).toBe('2쪽, 45건 가운데 21~40');
    expect(pageAnnouncement(3, 20, 45, 5)).toBe('3쪽, 45건 가운데 41~45');
  });
});

describe('C-4 첨부', () => {
  test('C-4 첨부 한도(ATT-2): 파일 10MB, 공개 합 50MB, 이름 255자', () => {
    expect(ATTACHMENT_LIMITS).toEqual({ maxFileBytes: 10 * 1024 * 1024, maxTotalBytes: 50 * 1024 * 1024, maxNameLength: 255 });
  });

  test('C-4 첨부 막는 이유: 이유마다 문구가 있고 서로 다르다, 크기 이유에는 한도 숫자', () => {
    const texts = Object.values(REJECT_TEXT);
    expect(texts).toHaveLength(4);
    expect(texts.every((t) => t.length > 0)).toBe(true);
    expect(new Set(texts).size).toBe(4);
    expect(REJECT_TEXT['too-large']).toContain('10MB');
    expect(REJECT_TEXT['total-too-large']).toContain('50MB');
    expect(REJECT_TEXT['name-too-long']).toContain('255');
  });

  test('C-4 첨부 서버 거절: 화면이 미리 막는 이유와 같은 code는 같은 문구, 그 밖의 아는 code는 자기 문구, 모르는 code는 서버 message', () => {
    const rejected = (code: string): Failure => ({ kind: 'rejected', status: 400, code, message: '서버 문구' });
    expect(uploadFailureText(rejected('FILE_TOO_LARGE'))).toBe(REJECT_TEXT['too-large']);
    expect(uploadFailureText(rejected('FILE_EMPTY'))).toBe(REJECT_TEXT.empty);
    expect(uploadFailureText(rejected('ISSUE_ATTACHMENTS_TOO_LARGE'))).toBe(REJECT_TEXT['total-too-large']);
    const quota = uploadFailureText(rejected('ATTACHMENT_QUOTA_EXCEEDED'));
    const closed = uploadFailureText(rejected('ISSUE_CLOSED'));
    expect([quota, closed].every((t) => t !== '서버 문구' && t.length > 0)).toBe(true);
    expect(quota).not.toBe(closed);
    expect(uploadFailureText(rejected('SOMETHING_NEW'))).toBe('서버 문구');
  });

  test('C-4 첨부 실패 종류: 413만 앞단 상한 문구, 나머지는 종류마다 화면 문구', () => {
    expect(uploadFailureText({ kind: 'tooLarge' })).toBe(TEXT.tooLarge);
    expect(uploadFailureText({ kind: 'rateLimited', seconds: 9 })).toBe(rateLimitedText(9));
    expect(uploadFailureText({ kind: 'invalid', fields: [], message: '칸 오류' })).toBe('칸 오류');
    expect(uploadFailureText({ kind: 'forbidden', code: 'FORBIDDEN' })).toBe(TEXT.forbidden);
    expect(uploadFailureText({ kind: 'unauthenticated' })).toBe(TEXT.saveSessionEnded);
    expect(uploadFailureText({ kind: 'notFound' })).toBe(TEXT.notFound);
    expect(uploadFailureText({ kind: 'server' })).toBe(TEXT.server);
    expect(uploadFailureText({ kind: 'network' })).toBe(TEXT.server);
  });

  test('C-4 공개 첨부 합: 지운 것과 내부 첨부는 50MB 합에 넣지 않는다', () => {
    expect(
      publicBytes([
        attachment({ id: 'a', size: 100 }),
        attachment({ id: 'b', size: 200, deleted: true }),
        attachment({ id: 'c', size: 400, internal: true }),
        attachment({ id: 'd', size: 800 }),
      ]),
    ).toBe(900);
    expect(publicBytes([])).toBe(0);
  });
});

describe('C-4 응답 분류', () => {
  test('C-4 failureOf 400: INVALID_INPUT에 fields가 있을 때만 칸 오류, fields가 비면 띠(rejected)', () => {
    const fields = [{ field: 'name', code: 'REQUIRED' }];
    expect(failureOf(answer(400), { code: 'INVALID_INPUT', message: '고쳐 주세요', fields })).toEqual({ kind: 'invalid', fields, message: '고쳐 주세요' });
    expect(failureOf(answer(400), { code: 'INVALID_INPUT', message: '고쳐 주세요', fields: [] })).toEqual({
      kind: 'rejected',
      status: 400,
      code: 'INVALID_INPUT',
      message: '고쳐 주세요',
    });
    expect(failureOf(answer(400), { code: 'OTHER', message: 'm', fields })).toMatchObject({ kind: 'rejected', code: 'OTHER' });
    expect(failureOf(answer(409), { code: 'INVALID_INPUT', message: 'm', fields })).toMatchObject({ kind: 'rejected', status: 409 });
  });

  test('C-4 failureOf 상태: 401·403·404·413·429는 제 종류, 500 이상은 본문이 있어도 서버 오류', () => {
    const body = { code: 'ISSUE_CLOSED', message: 'm' };
    expect(failureOf(answer(401), body)).toEqual({ kind: 'unauthenticated' });
    expect(failureOf(answer(404), body)).toEqual({ kind: 'notFound' });
    expect(failureOf(answer(413), body)).toEqual({ kind: 'tooLarge' });
    expect(failureOf(answer(429, { 'Retry-After': '5' }), body)).toEqual({ kind: 'rateLimited', seconds: 5 });
    expect(failureOf(answer(500), body)).toEqual({ kind: 'server' });
    expect(failureOf(answer(503), body)).toEqual({ kind: 'server' });
    expect(failureOf(answer(499), body)).toEqual({ kind: 'rejected', status: 499, code: 'ISSUE_CLOSED', message: 'm' });
  });

  test('C-4 failureOf 본문: code가 없거나 글자가 아니면 서버 오류로 본다', () => {
    expect(failureOf(answer(409), 'not json')).toEqual({ kind: 'server' });
    expect(failureOf(answer(409), null)).toEqual({ kind: 'server' });
    expect(failureOf(answer(409), { message: 'm' })).toEqual({ kind: 'server' });
    expect(failureOf(answer(409), { code: 7, message: 'm' })).toEqual({ kind: 'server' });
    expect(failureOf(answer(409), { code: 'ISSUE_CLOSED', message: 3 })).toEqual({ kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: '' });
    expect(failureOf(answer(403), { code: 'FORBIDDEN', message: 'm' })).toEqual({ kind: 'forbidden', code: 'FORBIDDEN' });
    expect(failureOf(answer(403), undefined)).toEqual({ kind: 'forbidden', code: '' });
  });

  test('C-4 Retry-After: 없으면 60초, 초는 올림하고 1초 아래로 내리지 않는다, HTTP 날짜도 읽고 못 읽으면 60초', () => {
    expect(retryAfter(answer(429))).toBe(60);
    expect(retryAfter(answer(429, { 'Retry-After': '2.2' }))).toBe(3);
    expect(retryAfter(answer(429, { 'Retry-After': '0' }))).toBe(1);
    expect(retryAfter(answer(429, { 'Retry-After': 'soon' }))).toBe(60);
    const at = new Date(Date.now() + 90_000).toUTCString();
    const seconds = retryAfter(answer(429, { 'Retry-After': at }));
    expect(seconds).toBeGreaterThanOrEqual(85);
    expect(seconds).toBeLessThanOrEqual(90);
    expect(retryAfter(answer(429, { 'Retry-After': new Date(Date.now() - 90_000).toUTCString() }))).toBe(1);
  });
});

describe('C-3 쪽 주소', () => {
  test('C-3 pageHref: 다른 조건은 두고 쪽만 바꾸며, 1쪽은 page를 빼고 조건이 없으면 경로만', () => {
    window.history.replaceState(null, '', '/issues?status=OPEN&page=3');
    expect(pageHref('/issues', 2)).toBe('/issues?status=OPEN&page=2');
    expect(pageHref('/issues', 1)).toBe('/issues?status=OPEN');

    window.history.replaceState(null, '', '/issues?page=3');
    expect(pageHref('/issues', 1)).toBe('/issues');
    expect(pageHref('/issues', 4)).toBe('/issues?page=4');
    window.history.replaceState(null, '', '/');
  });
});
