// screens.md P-4·P-5·C-4 첨부(ATT-1~5): 첨부 주소, 파일 하나를 multipart로 보내기, 50MB 합에 넣는 크기.
// 문구(uploadFailureText, REJECT_TEXT)는 두 앱의 messages.behavior.test.ts가 이미 본다. 여기서는 그 밖의 셋을 본다.
import { describe, expect, it, test } from 'vitest';
import { attachmentAddress, countedBytes, uploadFile } from './attachments';

describe('attachmentAddress', () => {
  test('ATT-4 첨부 주소: 앱의 문의 경로 뒤에 문의 키와 첨부 ID를 둔다', () => {
    expect(attachmentAddress('/api/portal/issues', 'ACME-7', 'a1')).toBe('/api/portal/issues/ACME-7/attachments/a1');
  });

  it.each([
    ['키와 ID의 빗금', 'A/B', 'x/y', '/base/A%2FB/attachments/x%2Fy'],
    ['물음표와 #', 'K?1', 'i#d', '/base/K%3F1/attachments/i%23d'],
    ['빈칸과 한글', '문 의', '첨 부', `/base/${encodeURIComponent('문 의')}/attachments/${encodeURIComponent('첨 부')}`],
  ])('ATT-4 첨부 주소: %s는 경로 한 칸 안에 머물게 바꿔 쓴다', (_what, key, id, address) => {
    expect(attachmentAddress('/base', key, id)).toBe(address);
  });
});

describe('uploadFile', () => {
  const file = new File(['hello'], '보고서 v2.txt', { type: 'text/plain' });

  test('ATT-1 파일 하나를 part `file`로 보낸다: 몸통의 file은 파일 이름이고, 보내는 형식은 그 파일 하나인 FormData다', async () => {
    const sent: { body: { file: string }; form: FormData }[] = [];

    const result = await uploadFile<{ id: string }>(file, (body, bodySerializer) => {
      sent.push({ body, form: bodySerializer() });
      return Promise.resolve({ data: { id: 'att-1' }, response: new Response(null, { status: 201 }) });
    });

    expect(sent).toHaveLength(1);
    expect(sent[0]?.body).toEqual({ file: '보고서 v2.txt' });
    const form = sent[0]?.form;
    expect(form).toBeInstanceOf(FormData);
    expect([...(form?.keys() ?? [])]).toEqual(['file']);
    const part = form?.get('file');
    expect(part).toBeInstanceOf(File);
    expect((part as File).name).toBe('보고서 v2.txt');
    expect(await (part as File).text()).toBe('hello');
    expect(result).toMatchObject({ ok: true, data: { id: 'att-1' } });
  });

  test('ATT-1 서버가 거절하면 그 code의 실패이고, 연결이 끊기면 network 실패다', async () => {
    const rejected = await uploadFile(file, () =>
      Promise.resolve({ error: { code: 'FILE_EMPTY', message: 'm' }, response: new Response(null, { status: 400 }) }),
    );
    const lost = await uploadFile(file, () => Promise.reject(new TypeError('Failed to fetch')));

    expect(rejected).toEqual({ ok: false, failure: { kind: 'rejected', status: 400, code: 'FILE_EMPTY', message: 'm' } });
    expect(lost).toEqual({ ok: false, failure: { kind: 'network' } });
  });
});

describe('countedBytes', () => {
  const items = [
    { name: 'a', deleted: false, size: 100 },
    { name: 'b', deleted: true, size: 1000 },
    { name: 'c', deleted: false, size: 10 },
    { name: 'd', deleted: false, size: 1 },
  ];

  test('ATT-2 공개 합: 지운 첨부는 넣지 않는다', () => {
    expect(countedBytes(items)).toBe(111);
  });

  test('ATT-2 공개 합: left가 고른 첨부도 빼고, 지운 첨부는 left가 고르지 않아도 뺀다', () => {
    expect(countedBytes(items, (a) => a.name === 'c')).toBe(101);
    expect(countedBytes(items, () => false)).toBe(111);
    expect(countedBytes(items, () => true)).toBe(0);
  });

  test('ATT-2 공개 합: 첨부가 없으면 0이다', () => {
    expect(countedBytes([])).toBe(0);
  });
});
