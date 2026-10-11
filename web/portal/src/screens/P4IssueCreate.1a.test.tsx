// 1a screens.md 2절 P-4 문의 등록: 멈춘·보관된 고객사는 고를 수 없고 까닭을 적는다(S1A-AC-46, 49).
// 한 번에 붙이는 파일은 20개까지이고 앱의 413(JSON 본문)도 같은 문구다. 본문이 JSON이 아닌 413은 앞단 상한 문구다(S1A-AC-65).
import { describe, expect, test } from 'vitest';
import {
  ACME,
  alertWith,
  button,
  errors,
  eventually,
  field,
  file,
  on,
  pageText,
  renderAt,
  screen,
  state,
  text,
  type Company,
} from '../__tests__/portal';

const SUSPENDED: Company = { code: 'BETA', name: '베타상사', status: 'SUSPENDED', manager: false };
const ARCHIVED: Company = { code: 'OLD', name: '올드', status: 'ARCHIVED', manager: false };
const NOT_ACTIVE_TEXT = '새 문의를 받지 않습니다';
const TOO_MANY_TEXT = '한 번에 20개까지 붙일 수 있습니다';
const PROXY_LIMIT_TEXT = '파일이 서버 앞단의 크기 상한에 걸렸습니다';

const title = () => field(/^제목/, 'input');
const attachInput = () => field(/첨부/, 'input[type="file"]');
const files = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => file(`f${String(from + i)}.txt`));
const fileRows = () => screen.queryAllByRole('listitem').filter((li) => /f\d+\.txt/.test(text(li)));

describe('P-4 문의 등록 (1a)', () => {
  test('S1A-AC-46 P-4 고객사 고르기: 멈춘·보관된 고객사는 고를 수 없게 보이고 "새 문의를 받지 않습니다"를 적는다', async () => {
    state.companies = [ACME, SUSPENDED, ARCHIVED];
    renderAt('/issues/new');

    const company = await field<HTMLSelectElement>(/^고객사/, 'select');
    const option = (code: string) => Array.from(company.options).find((o) => o.value === code);
    expect(option(ACME.code)?.disabled, '사용 중인 고객사').toBe(false);
    expect(option(SUSPENDED.code)?.disabled, '멈춘 고객사').toBe(true);
    expect(option(ARCHIVED.code)?.disabled, '보관된 고객사').toBe(true);
    expect(pageText()).toContain(NOT_ACTIVE_TEXT);
  });

  test('S1A-AC-46 P-4 소속 고객사가 모두 멈춤·보관: 등록 버튼 대신 "새 문의를 받지 않습니다" 안내를 보인다', async () => {
    state.companies = [SUSPENDED, ARCHIVED];
    renderAt('/issues/new');

    await eventually(() => {
      expect(pageText()).toContain(NOT_ACTIVE_TEXT);
    });
    expect(screen.queryByRole('button', { name: '올리기' })).toBeNull();
  });

  test('S1A-AC-65 P-4 파일 21개째: 20개까지는 받고, 21개째는 더하지 않고 "한 번에 20개까지 붙일 수 있습니다"를 보인다', async () => {
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), files(1, 20));
    await eventually(() => {
      expect(fileRows()).toHaveLength(20);
    });
    expect(pageText()).not.toContain(TOO_MANY_TEXT);

    await user.upload(await attachInput(), files(21, 21));

    await eventually(() => {
      expect(pageText()).toContain(TOO_MANY_TEXT);
    });
    expect(fileRows()).toHaveLength(20);
    expect(pageText()).not.toContain('f21.txt');
  });

  test.each([
    ['앱의 413(JSON 본문): 같은 문구 "한 번에 20개까지 붙일 수 있습니다"를 띠로 보이고 입력은 지우지 않는다', () => errors.payloadTooLarge(), TOO_MANY_TEXT],
    [
      '앞단의 413(JSON이 아닌 본문): "20개까지"가 아니라 "파일이 서버 앞단의 크기 상한에 걸렸습니다"를 보인다',
      () => errors.proxyPayloadTooLarge(),
      PROXY_LIMIT_TEXT,
    ],
  ])('S1A-AC-65 P-4 %s', async (_, answer, shown) => {
    on('post', '/api/portal/issues', answer);
    const { user } = renderAt('/issues/new');
    await user.type(await title(), '프린터가 안 됩니다');
    await user.upload(await attachInput(), files(1, 2));
    await user.click(await button('올리기'));

    await alertWith(shown);
    for (const other of [TOO_MANY_TEXT, PROXY_LIMIT_TEXT].filter((t) => t !== shown)) {
      expect(pageText()).not.toContain(other);
    }
    expect(await title()).toHaveValue('프린터가 안 됩니다');
    expect(fileRows()).toHaveLength(2);
    expect(window.location.pathname).toBe('/issues/new');
  });
});
