// screens.md 4절 C-10 구성원 목록(관리자). SMTP 설정 여부는 관리자용 설정 요약(시스템 상태 API의 mail.enabled)으로 안다.
// 초대 링크는 SMTP가 없을 때만, 한 번만 보인다(S0-AC-38, INVITE-6).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  INVITE_LINK,
  T0,
  account,
  atPath,
  button,
  callsTo,
  dialog,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  invitation,
  isBlocked,
  on,
  openDialog,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  setSmtp,
  state,
  statusWith,
  text,
  within,
} from '../__tests__/console';

type User = ReturnType<typeof renderAt>['user'];

const PENDING = account({ id: 21, email: 'park@acme.test', name: '박초대', role: 'CUSTOMER', status: 'INVITED' });

/** One account waiting to accept, shown in both lists the screen may read. */
function seedPending() {
  state.accounts = [account(), account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' }), PENDING];
  state.invitations = [invitation({ accountId: 21, email: 'park@acme.test', name: '박초대' })];
}

async function rowOf(name: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name))),
    `${name} 줄`,
  );
}

const inviteOpen = () => button(/^초대$/);
const copyRowButtons = () => screen.queryAllByRole('button', { name: '초대 링크 복사' });

function inDialog(box: HTMLElement) {
  const input = (label: RegExp) =>
    found(() => within(box).queryAllByLabelText<HTMLInputElement>(label, { selector: 'input' })[0], `대화상자 칸 ${String(label)}`);
  return {
    email: () => input(/^이메일/),
    name: () => input(/^이름/),
    role: (name: '고객' | '상담원' | '관리자') => found(() => within(box).queryByRole('radio', { name }), `역할 "${name}"`),
    companies: () => found(() => within(box).queryByRole('group', { name: '소속 고객사' }), 'fieldset "소속 고객사"'),
    submit: () =>
      found(
        () => within(box).queryAllByRole('button').find((b) => /^(초대|보내기|초대 보내기)/.test(text(b))),
        '대화상자의 보내기 버튼',
      ),
  };
}

async function openInvite(user: User) {
  await user.click(await inviteOpen());
  const box = await dialog();
  return { box, ...inDialog(box) };
}

async function fillCustomer(user: User, d: ReturnType<typeof inDialog>) {
  await user.type(await d.email(), 'choi@acme.test');
  await user.type(await d.name(), '최고객');
  await user.click(await d.role('고객'));
  await user.click(within(await d.companies()).getByRole('checkbox', { name: /에이크미/ }));
}

describe('C-10 구성원 목록', () => {
  test('C-10 데이터 있음: 처음에는 첫 관리자 한 줄, 표에 이름·이메일·역할·만든 시각, 위에 "초대"', async () => {
    renderAt('/members');

    const row = await rowOf('최관리');
    for (const part of ['최관리', 'admin@popdesk.test', '관리자']) expect(text(row)).toContain(part);
    expect(row.querySelector('time')?.getAttribute('datetime'), '만든 시각 <time>').toBeTruthy();
    expect(new Date(row.querySelector('time')?.getAttribute('datetime') ?? '').getTime()).toBe(new Date(T0).getTime());
    await inviteOpen();
    const table = await found(() => screen.queryByRole('table'), '구성원 표');
    const headers = within(table).getAllByRole('columnheader').map(text);
    for (const h of ['이름', '이메일', '역할']) expect(headers.some((x) => x.includes(h)), `열 머리 "${h}"`).toBe(true);
  });

  test('C-10 데이터 있음: 수락 전 계정에 "수락 전" 표시', async () => {
    seedPending();
    renderAt('/members');

    expect(text(await rowOf('박초대'))).toContain('수락 전');
    expect(text(await rowOf('이상담'))).not.toContain('수락 전');
  });

  test('C-10 초대: 대화상자에 이메일, 이름, 역할(고객·상담원·관리자) 라디오 묶음. 열면 첫 칸에 포커스', async () => {
    const { user } = renderAt('/members');
    const d = await openInvite(user);

    const email = await d.email();
    await d.name();
    await d.role('고객');
    await d.role('상담원');
    await d.role('관리자');
    expect(within(d.box).getByRole('radiogroup')).toBeInTheDocument();
    await eventually(() => {
      expect(document.activeElement).toBe(email);
    });
  });

  test('C-10 초대: 고객을 고르면 fieldset "소속 고객사" 체크 묶음이 나오고, 상담원이면 없다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt('/members');
    const d = await openInvite(user);

    await user.click(await d.role('상담원'));
    expect(within(d.box).queryByRole('group', { name: '소속 고객사' })).toBeNull();
    await user.click(await d.role('고객'));
    const group = await d.companies();
    expect(group.tagName).toBe('FIELDSET');
    expect(within(group).getAllByRole('checkbox')).toHaveLength(2);
    expect(within(group).getByRole('checkbox', { name: /에이크미/ })).toBeInTheDocument();
    expect(within(group).getByRole('checkbox', { name: /베타상사/ })).toBeInTheDocument();
  });

  test('C-10 초대: 고객사가 하나도 없으면 "먼저 고객사를 만드세요"와 C-8 링크, 고객 역할은 고를 수 있다', async () => {
    state.companies = [];
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    const customer = await d.role('고객');
    await user.click(customer);

    expect(customer).toBeChecked();
    await eventually(() => {
      expect(text(d.box)).toContain('먼저 고객사를 만드세요');
    });
    const toCompanies = within(d.box).queryAllByRole('link').filter((a) => hrefPath(a) === '/companies');
    expect(toCompanies.length, 'C-8로 가는 링크').toBeGreaterThan(0);
  });

  test('C-10 초대 성공(고객): 이메일, 이름, 역할, 고른 고객사 코드를 보낸다', async () => {
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());

    const [post] = await sent('POST', '/api/console/invitations');
    expect(await post?.body).toEqual({ email: 'choi@acme.test', name: '최고객', role: 'CUSTOMER', companyCodes: ['ACME'] });
  });

  test('C-10 초대 성공(SMTP 있음): 대화상자를 닫고 토스트 "초대 메일을 보냈습니다", 목록을 다시 불러온다', async () => {
    const { user } = renderAt('/members');
    await rowOf('최관리');
    const loads = callsTo('GET', /\/api\/console\/(accounts|invitations)$/).length;
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());

    await statusWith('초대 메일을 보냈습니다');
    await eventually(() => {
      expect(openDialog()).toBeNull();
      expect(callsTo('GET', /\/api\/console\/(accounts|invitations)$/).length).toBeGreaterThan(loads);
    });
    expect(pageText()).not.toContain(INVITE_LINK);
  });

  test('S0-AC-38 C-10 초대 성공(SMTP 없음): 대화상자를 닫지 않고 안내, 읽기 전용 링크 칸과 "복사", 이번에만 보인다는 안내', async () => {
    setSmtp(false);
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());

    await eventually(() => {
      expect(text(d.box)).toContain('메일 설정이 없어 초대 메일이 나가지 않았습니다. 아래 링크를 복사해 직접 전하세요.');
    });
    const linkField = within(d.box).getAllByRole<HTMLInputElement>('textbox').find((el) => el.value === INVITE_LINK);
    expect(linkField, '초대 링크 칸').toBeDefined();
    expect(linkField).toHaveAttribute('readonly');
    expect(within(d.box).getByRole('button', { name: '복사' })).toBeInTheDocument();
    expect(text(d.box)).toMatch(/이번에만|지금만|한 번만/);
    expect(openDialog()).not.toBeNull();
  });

  test('S0-AC-38 C-10 링크 한 번만: 대화상자를 닫으면 링크가 화면에서 사라지고 주소창에 남지 않는다', async () => {
    setSmtp(false);
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());
    await eventually(() => {
      expect(within(d.box).getAllByRole<HTMLInputElement>('textbox').some((el) => el.value === INVITE_LINK)).toBe(true);
    });

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    const values = Array.from(document.querySelectorAll('input, textarea')).map((el) => (el as HTMLInputElement).value);
    expect(values.filter((v) => v.includes('inv-Zq81mWc4Lp0a'))).toEqual([]);
    expect(document.body.innerHTML).not.toContain('inv-Zq81mWc4Lp0a');
    expect(window.location.href).not.toContain('inv-Zq81mWc4Lp0a');
  });

  test('S0-AC-38 C-10 초대 링크 복사(SMTP 없음): 수락 전 줄에만 버튼', async () => {
    setSmtp(false);
    seedPending();
    renderAt('/members');

    const pending = await rowOf('박초대');
    await eventually(() => {
      expect(within(pending).queryByRole('button', { name: '초대 링크 복사' })).not.toBeNull();
    });
    expect(within(await rowOf('최관리')).queryByRole('button', { name: '초대 링크 복사' })).toBeNull();
    expect(within(await rowOf('이상담')).queryByRole('button', { name: '초대 링크 복사' })).toBeNull();
    expect(copyRowButtons()).toHaveLength(1);
  });

  test('S0-AC-38 C-10 초대 링크 복사(SMTP 있음): 버튼을 숨긴다', async () => {
    setSmtp(true);
    seedPending();
    renderAt('/members');

    expect(text(await rowOf('박초대'))).toContain('수락 전');
    await sent('GET', '/api/console/system/status');
    expect(copyRowButtons()).toHaveLength(0);
  });

  test('S0-AC-38 C-10 초대 링크 복사: 먼저 "새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다."를 묻고, 확인하면 만든 링크를 대화상자에 한 번 보인다', async () => {
    setSmtp(false);
    seedPending();
    const { user } = renderAt('/members');
    const pending = await rowOf('박초대');
    await user.click(await found(() => within(pending).queryByRole('button', { name: '초대 링크 복사' }), '초대 링크 복사'));

    const ask = await dialog();
    expect(text(ask)).toContain('새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다.');
    expect(callsTo('POST', '/api/console/invitations/21/link'), '확인 전').toHaveLength(0);
    const confirm = within(ask).queryAllByRole('button').find((b) => !/취소|닫기/.test(b.getAttribute('aria-label') ?? text(b)));
    expect(confirm, '확인 버튼').toBeDefined();
    await user.click(confirm as HTMLElement);

    await sent('POST', '/api/console/invitations/21/link');
    await eventually(() => {
      const shown = screen.queryAllByRole<HTMLInputElement>('textbox').filter((el) => el.value === INVITE_LINK);
      expect(shown, '대화상자의 링크 칸').toHaveLength(1);
      expect(shown[0]?.closest('[role="dialog"], [role="alertdialog"]')).not.toBeNull();
    });
  });

  test('S0-AC-38 C-10 초대 링크 복사 취소: 묻는 대화상자에서 취소하면 링크를 만들지 않는다', async () => {
    setSmtp(false);
    seedPending();
    const { user } = renderAt('/members');
    const pending = await rowOf('박초대');
    await user.click(await found(() => within(pending).queryByRole('button', { name: '초대 링크 복사' }), '초대 링크 복사'));
    await user.click(within(await dialog()).getByRole('button', { name: '취소' }));

    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    expect(callsTo('POST', '/api/console/invitations/21/link')).toHaveLength(0);
  });

  test('C-10 복사: 클립보드에 링크를 넣고 토스트 "복사했습니다"', async () => {
    setSmtp(false);
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());
    await user.click(await found(() => within(d.box).queryByRole('button', { name: '복사' }), '"복사" 버튼'));

    await statusWith('복사했습니다');
    expect(await navigator.clipboard.readText()).toBe(INVITE_LINK);
  });

  test('C-10 복사: 클립보드를 쓸 수 없으면 링크 칸을 전체 선택해 두고 "Ctrl+C로 복사하세요"', async () => {
    setSmtp(false);
    const { user } = renderAt('/members');
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')) },
    });
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());
    await user.click(await found(() => within(d.box).queryByRole('button', { name: '복사' }), '"복사" 버튼'));

    await eventually(() => {
      expect(text(d.box)).toContain('Ctrl+C로 복사하세요');
    });
    const linkField = within(d.box).getAllByRole<HTMLInputElement>('textbox').find((el) => el.value === INVITE_LINK);
    expect(linkField, '링크 칸').toBeDefined();
    expect(document.activeElement).toBe(linkField);
    expect(linkField?.selectionStart).toBe(0);
    expect(linkField?.selectionEnd).toBe(INVITE_LINK.length);
  });

  test('C-10 초대 저장 중: 보내기 버튼 막힘, 다시 보내지 않는다', async () => {
    const release = gate();
    on('post', '/api/console/invitations', async () => {
      await release.opened;
      return HttpResponse.json({ accountId: 30, inviteLink: null }, { status: 201 });
    });
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    const submit = await d.submit();
    await user.click(submit);

    await eventually(() => {
      expect(isBlocked(submit)).toBe(true);
    });
    await user.click(submit);
    expect(callsTo('POST', '/api/console/invitations')).toHaveLength(1);
    release.open();
  });

  test('C-10 초대 400 fields: 이메일 모양, 이름 빈 값, 고객인데 고객사 없음을 각 자리에, 포커스는 첫 틀린 칸', async () => {
    on('post', '/api/console/invitations', () =>
      errors.invalid([
        { field: 'email', code: 'INVALID_FORMAT' },
        { field: 'name', code: 'BLANK' },
        { field: 'companyCodes', code: 'REQUIRED' },
      ]),
    );
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await user.type(await d.email(), 'not-an-email');
    await user.type(await d.name(), ' ');
    await user.click(await d.role('고객'));
    await user.click(await d.submit());

    const email = await d.email();
    await expectFieldError(email);
    await expectFieldError(await d.name());
    const group = await d.companies();
    await eventually(() => {
      const wired = group.getAttribute('aria-invalid') === 'true' || (group.getAttribute('aria-describedby') ?? '') !== '';
      expect(wired, '소속 고객사 묶음 오류').toBe(true);
    });
    expect(document.activeElement).toBe(email);
  });

  test('C-10 초대 409: 이메일 칸 아래 "이미 등록된 이메일입니다.", 입력은 그대로', async () => {
    on('post', '/api/console/invitations', () => errors.emailTaken());
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());

    const email = await d.email();
    await expectFieldError(email, '이미 등록된 이메일입니다.');
    expect(email).toHaveValue('choi@acme.test');
    expect(await d.name()).toHaveValue('최고객');
  });

  test('C-10 쪽 넘기기: 2쪽을 page=2로 부르고 "2쪽, 25건 가운데 21~25"를 알린다', async () => {
    const people = (from: number, count: number) =>
      Array.from({ length: count }, (_, i) => account({ id: 100 + from + i, name: `사람 ${String(from + i)}`, email: `p${String(from + i)}@acme.test`, role: 'AGENT' }));
    on('get', '/api/console/accounts', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return HttpResponse.json(paged(page === 2 ? people(21, 5) : people(1, 20), { page, totalElements: 25, totalPages: 2 }));
    });
    const { user } = renderAt('/members');
    await rowOf('사람 20');
    await user.click(await found(() => screen.queryByRole('link', { name: /^2(쪽)?$/ }), '2쪽 링크'));

    await rowOf('사람 21');
    await statusWith('2쪽, 25건 가운데 21~25');
  });

  test('C-10 초대 성공 뒤 다른 화면으로 가도 링크를 남기지 않는다(MAIL-3)', async () => {
    setSmtp(false);
    const { user } = renderAt('/members');
    const d = await openInvite(user);
    await fillCustomer(user, d);
    await user.click(await d.submit());
    await eventually(() => {
      expect(text(d.box)).toContain('메일 설정이 없어');
    });
    await user.keyboard('{Escape}');
    await user.click(await found(() => screen.queryByRole('link', { name: '문의' }), '메뉴 "문의"'));

    await atPath('/issues');
    expect(document.body.innerHTML).not.toContain('inv-Zq81mWc4Lp0a');
  });

  test('C-10 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt('/members');
    await rowOf('최관리');

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-10 axe: 목록과 고객을 고른 초대 대화상자에 접근성 위반이 없다', async () => {
    seedPending();
    const { user } = renderAt('/members');
    await rowOf('박초대');
    const d = await openInvite(user);
    await user.click(await d.role('고객'));
    await d.companies();

    await expectNoAxeViolations();
  });
});
