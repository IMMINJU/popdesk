// screens.md 4절 C-8 고객사 목록(관리자). 상담원이 보는 경우는 adminOnly.test.tsx에 있다.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  ACME,
  alertWith,
  atPath,
  button,
  callsTo,
  company,
  dialog,
  draftKept,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  isBlocked,
  on,
  openDialog,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  text,
  useFakeClock,
  within,
  type Company,
} from '../__tests__/console';

type User = ReturnType<typeof renderAt>['user'];

function many(count: number, from = 1): Company[] {
  return Array.from({ length: count }, (_, i) => company({ code: `C${String(from + i)}`, name: `고객사 ${String(from + i)}` }));
}

async function rowOf(code: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(code))),
    `${code} 줄`,
  );
}

const createButton = () => button('고객사 만들기');
const pageLink = (n: number) => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) });

function inDialog(box: HTMLElement) {
  const fieldIn = (label: RegExp) =>
    found(() => within(box).queryAllByLabelText<HTMLInputElement>(label, { selector: 'input' })[0], `대화상자 칸 ${String(label)}`);
  return {
    name: () => fieldIn(/^이름/),
    code: () => fieldIn(/^코드/),
    legalName: () => fieldIn(/^회사명/),
    submit: () => found(() => within(box).queryByRole('button', { name: /^만들기/ }), '대화상자 "만들기" 버튼'),
  };
}

async function openCreate(user: User) {
  await user.click(await createButton());
  const box = await dialog();
  return { box, ...inDialog(box) };
}

async function fillCreate(user: User, d: ReturnType<typeof inDialog>, values = { name: '감마', code: 'gamma', legalName: '감마 주식회사' }) {
  await user.type(await d.name(), values.name);
  await user.type(await d.code(), values.code);
  await user.type(await d.legalName(), values.legalName);
}

describe('C-8 고객사 목록', () => {
  test('C-8 데이터 있음: 표에 코드, 이름, 회사명, 상태 배지(사용 중·멈춤·보관)와 위에 "고객사 만들기"', async () => {
    state.companies = [
      ACME,
      company({ code: 'BETA', name: '베타상사', legalName: '베타상사 유한회사', status: 'SUSPENDED' }),
      company({ code: 'OLD', name: '올드', legalName: '올드 주식회사', status: 'ARCHIVED' }),
    ];
    renderAt('/companies');

    const acme = text(await rowOf('ACME'));
    for (const part of ['ACME', '에이크미', '에이크미 주식회사', '사용 중']) expect(acme).toContain(part);
    expect(text(await rowOf('BETA'))).toContain('멈춤');
    expect(text(await rowOf('OLD'))).toContain('보관');
    await createButton();
  });

  test('C-8 데이터 있음: 줄에서 C-9 고객사 상세로 가는 링크', async () => {
    renderAt('/companies');

    const row = await rowOf('ACME');
    const toDetail = within(row).queryAllByRole('link').filter((a) => hrefPath(a) === '/companies/ACME');
    expect(toDetail.length, '/companies/ACME 링크').toBe(1);
  });

  test('C-8 빈 목록: "첫 고객사를 만드세요. 고객사를 만들어야 고객을 초대할 수 있습니다."와 "고객사 만들기"', async () => {
    state.companies = [];
    renderAt('/companies');

    await eventually(() => {
      expect(pageText()).toContain('첫 고객사를 만드세요. 고객사를 만들어야 고객을 초대할 수 있습니다.');
    });
    await createButton();
  });

  test('C-8 처음 불러옴: 300ms가 넘으면 aria-hidden 표 뼈대, main은 aria-busy', async () => {
    useFakeClock();
    const release = gate();
    on('get', '/api/console/companies', async () => {
      await release.opened;
      return HttpResponse.json(paged(state.companies));
    });
    renderAt('/companies', { fakeTimers: true });

    const main = await found(() => screen.queryByRole('main'), '본문 main');
    act(() => {
      vi.advanceTimersByTime(350);
    });
    await eventually(() => {
      expect(main).toHaveAttribute('aria-busy', 'true');
      expect(main.querySelectorAll('[aria-hidden="true"]').length, '표 뼈대').toBeGreaterThan(0);
    });
    release.open();
    await rowOf('ACME');
  });

  test('C-8 만들기: 대화상자에 이름·코드·회사명, 코드 칸에 "나중에 바꿀 수 없습니다" 안내, 열면 첫 칸에 포커스', async () => {
    const { user } = renderAt('/companies');
    const { box, name, code, legalName } = await openCreate(user);

    expect(within(box).queryByRole('heading')).not.toBeNull();
    const nameField = await name();
    await code();
    await legalName();
    expect(text(box)).toContain('나중에 바꿀 수 없습니다');
    await eventually(() => {
      expect(document.activeElement).toBe(nameField);
    });
  });

  test('C-8 만들기: 코드는 입력하면 대문자로 바꿔 보이고, 대문자로 보낸 뒤 C-9로 가서 토스트', async () => {
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await fillCreate(user, d);
    expect(await d.code()).toHaveValue('GAMMA');
    await user.click(await d.submit());

    const [post] = await sent('POST', '/api/console/companies');
    expect(await post?.body).toEqual({ name: '감마', code: 'GAMMA', legalName: '감마 주식회사' });
    await atPath('/companies/GAMMA');
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    await found(() => screen.queryAllByRole('status').find((el) => text(el) !== ''), '성공 토스트(role="status")');
  });

  test('C-8 만들기 저장 중: 만들기 버튼은 막히고 닫기는 된다', async () => {
    const release = gate();
    on('post', '/api/console/companies', async () => {
      await release.opened;
      return HttpResponse.json(company({ code: 'GAMMA', name: '감마', legalName: '감마 주식회사' }), { status: 201 });
    });
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await fillCreate(user, d);
    const submit = await d.submit();
    await user.click(submit);

    // 2절: 저장 중 버튼은 aria-disabled로 막고 disabled를 쓰지 않는다(포커스를 잃지 않게).
    await eventually(() => {
      expect(submit).toHaveAttribute('aria-disabled', 'true');
    });
    expect(submit, '저장 중 버튼에 disabled').not.toBeDisabled();
    await user.click(submit);
    expect(callsTo('POST', '/api/console/companies')).toHaveLength(1);
    const close = within(d.box).queryAllByRole('button').find((b) => /닫기|취소/.test(b.getAttribute('aria-label') ?? text(b)));
    expect(close, '닫기 버튼').toBeDefined();
    expect(isBlocked(close as HTMLElement), '저장 중에도 닫기는 된다').toBe(false);
    release.open();
  });

  test('C-8 만들기 400 fields: 이름·회사명 빈 값, 코드 모양을 칸 아래에, 포커스는 첫 틀린 칸', async () => {
    on('post', '/api/console/companies', () =>
      errors.invalid([
        { field: 'name', code: 'TOO_LONG' },
        { field: 'code', code: 'INVALID_FORMAT' },
        { field: 'legalName', code: 'BLANK' },
      ]),
    );
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await fillCreate(user, d, { name: '감마', code: 'g', legalName: ' ' });
    await user.click(await d.submit());

    const nameField = await d.name();
    await expectFieldError(nameField);
    await expectFieldError(await d.code());
    await expectFieldError(await d.legalName());
    expect(document.activeElement).toBe(nameField);
    expect(openDialog(), '대화상자는 열린 채').not.toBeNull();
  });

  test('C-8 만들기 409: 코드 칸 아래 "이미 쓰는 코드입니다.", 포커스는 코드 칸, 입력은 그대로', async () => {
    on('post', '/api/console/companies', () => errors.companyCodeTaken());
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await fillCreate(user, d, { name: '에이크미 둘', code: 'ACME', legalName: '에이크미 2' });
    await user.click(await d.submit());

    const code = await d.code();
    await expectFieldError(code, '이미 쓰는 코드입니다.');
    expect(document.activeElement).toBe(code);
    expect(await d.name()).toHaveValue('에이크미 둘');
    expect(window.location.pathname).toBe('/companies');
  });

  test('C-8 대화상자 키보드: 포커스가 대화상자 안을 돈다', async () => {
    const { user } = renderAt('/companies');
    const { box } = await openCreate(user);

    for (let i = 0; i < 12; i += 1) {
      await user.tab();
      expect(box.contains(document.activeElement), `Tab ${String(i + 1)}번 뒤 포커스`).toBe(true);
    }
    for (let i = 0; i < 6; i += 1) {
      await user.tab({ shift: true });
      expect(box.contains(document.activeElement), `Shift+Tab ${String(i + 1)}번 뒤 포커스`).toBe(true);
    }
  });

  test('C-8 대화상자 Esc: 쓴 값이 없으면 닫고 포커스를 "고객사 만들기"로 돌린다', async () => {
    const { user } = renderAt('/companies');
    await openCreate(user);
    await user.keyboard('{Escape}');

    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    expect(document.activeElement).toBe(await createButton());
  });

  test('C-8 대화상자 Esc: 쓰던 값이 있으면 닫기 전에 묻는다', async () => {
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await user.type(await d.name(), '쓰다 만 이름');
    await user.keyboard('{Escape}');

    await eventually(() => {
      const boxes = [...screen.queryAllByRole('dialog'), ...screen.queryAllByRole('alertdialog')];
      expect(boxes.some((b) => b !== d.box), '묻는 확인 대화상자').toBe(true);
    });
    expect(draftKept('쓰다 만 이름'), '묻는 동안 쓰던 값').toBe(true);
    expect(callsTo('POST', '/api/console/companies')).toHaveLength(0);
  });

  test('C-8 쪽 넘기기: 1쪽뿐이면 숨기고, 여러 쪽이면 2쪽을 page=2로 부르고 알린다', async () => {
    on('get', '/api/console/companies', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return HttpResponse.json(paged(many(page === 2 ? 5 : 20, (page - 1) * 20 + 1), { page, totalElements: 25, totalPages: 2 }));
    });
    const { user } = renderAt('/companies');
    await rowOf('C20');
    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await rowOf('C21');
    const lists = await sent('GET', '/api/console/companies', 2);
    expect(new URL(lists[lists.length - 1]?.url ?? '').searchParams.get('page')).toBe('2');
    await statusWith('2쪽, 25건 가운데 21~25');
    expect(pageLink(2)).toHaveAttribute('aria-current', 'page');
  });

  test('C-8 403 만들기: "이 작업을 할 권한이 없습니다." 띠, 입력은 그대로', async () => {
    on('post', '/api/console/companies', () => errors.forbidden());
    const { user } = renderAt('/companies');
    const d = await openCreate(user);
    await fillCreate(user, d);
    await user.click(await d.submit());

    await alertWith('이 작업을 할 권한이 없습니다.');
    expect(await d.name()).toHaveValue('감마');
  });

  test('C-8 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt('/companies');
    await rowOf('ACME');

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-8 axe: 목록과 열린 만들기 대화상자에 접근성 위반이 없다', async () => {
    const { user } = renderAt('/companies');
    await rowOf('ACME');
    await openCreate(user);

    await expectNoAxeViolations();
  });
});
