// b5 묶음 ④ 몫(acceptance.md 4절 7): 콘솔 관리 쪽 화면 C-5, C-8~C-14, C-18을 영어로 열어 한글이 없는지 본다(B5-AC-05).
// 관리자(영어 계정)로 연다. 영어 글은 구현 탭이 짓는다(4절 6). 그래서 영어 문구로 찾지 않는다: 화면이 뜬 것은 영어 견본
// 데이터로 알고, 단추 이름이 꼭 필요하면 "지금 한국어 또는 용어표(screens.md 3절)의 영어"로 찾는다.
import { describe, expect, test } from 'vitest';
import {
  channel,
  company,
  dialog,
  errors,
  eventually,
  failedDelivery,
  found,
  heading1,
  on,
  renderAt,
  screen,
  state,
  text,
  within,
} from '../__tests__/console';
import { route, settle } from '../__tests__/console1a';
import { AUDIT, audit, entry, type AuditActor } from '../__tests__/console1b';
import { pending, request } from '../__tests__/console2';
import { cleanupJob, status } from '../__tests__/console4b';
import { label, s6a } from '../__tests__/console6a';
import { s7a, trashed } from '../__tests__/console7a';
import { STATS, s7b, stat } from '../__tests__/console7b';
import { SLA_POLICY, offPolicy, onPolicy, s8a } from '../__tests__/console8a';
import { BRANDING, SQUARE_LOGO, WIDE_LOGO, defaultBranding, publicOf, s9, type Branding } from '../__tests__/console9';
import { companyExport, exportId, failed, running, s10b } from '../__tests__/console10b';
import { oidcOn } from '../__tests__/console11';
import { mailInOn } from '../__tests__/console12';
import {
  ACME_EN,
  ADMIN_PERSON_EN,
  AGENT_EN,
  BETA_EN,
  accountEn,
  expectEnglishPage,
  expectNoHangul,
  invitationEn,
  memberEn,
  openInEnglish,
} from '../__tests__/consoleB5';

type User = ReturnType<typeof renderAt>['user'];

const ADMIN_ACTOR_EN: AuditActor = { accountId: 1, name: 'Morgan Admin', email: 'admin@popdesk.test' };
const AGENT_ACTOR_EN: AuditActor = { accountId: 2, name: 'Lee Agent', email: 'lee@popdesk.test' };
const SYSTEM_ACTOR: AuditActor = { accountId: 0, name: null, email: null };

/** Waits until the page shows `marker` (English sample data), so the screen has read what it shows. */
async function shows(marker: string) {
  await eventually(() => {
    expect(text(document.body), `화면에 "${marker}"`).toContain(marker);
  });
}

/** Waits for a non-empty `role="alert"` band. */
const band = (what: string) => found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), what);

/** A button or link whose name is the Korean of today or the English of the glossary. */
const control = (name: RegExp, root: HTMLElement = document.body) =>
  found(() => within(root).queryAllByRole('button', { name })[0] ?? within(root).queryAllByRole('link', { name })[0], `단추 ${String(name)}`);

/** Opens `path` as the English admin and waits for its h1 and `marker`. */
async function open(path: string, marker: string): Promise<User> {
  const { user } = renderAt(path);
  await heading1();
  await shows(marker);
  await settle();
  return user;
}

describe('B5 ④ 콘솔 C-5 통계를 영어로', () => {
  const STATS_EN = [
    stat({ companyCode: 'ACME', companyName: 'Acme' }),
    stat({ companyCode: 'BETA', companyName: 'Beta Trading', open: 2, inProgress: 0, resolved: 1, closed: 0 }),
    stat({ companyCode: 'OLD', companyName: 'Old Trading', companyStatus: 'ARCHIVED', open: 0, inProgress: 0, resolved: 0, closed: 7 }),
    stat({ companyCode: 'ZETA', companyName: 'Zeta Soft', companyStatus: 'SUSPENDED', open: 9, inProgress: 1, resolved: 0, closed: 1234 }),
  ];

  test('B5-AC-05 C-5 수가 있는 표(보관·정지 고객사 포함): 한글이 없고 html lang, document.title이 영어다', async () => {
    openInEnglish();
    s7b.stats = STATS_EN;
    await open('/stats', 'Zeta Soft');

    expectNoHangul('C-5 표');
    expectEnglishPage('C-5 표');
  });

  test('B5-AC-05 C-5 빈 목록: 한글이 없다', async () => {
    openInEnglish();
    s7b.stats = [];
    renderAt('/stats');
    await heading1();
    await settle();

    expectNoHangul('C-5 빈 목록');
  });

  test('B5-AC-05 C-5 상담원(배정된 고객사가 없음)의 빈 목록: 한글이 없다', async () => {
    openInEnglish({ ...AGENT_EN, companies: [] });
    s7b.stats = [];
    renderAt('/stats');
    await heading1();
    await settle();

    expectNoHangul('C-5 상담원 빈 목록');
  });

  test('B5-AC-05 C-5 읽기 실패(500): 오류 띠와 다시 불러오기 단추가 영어다', async () => {
    openInEnglish();
    on('get', STATS, () => errors.internal());
    renderAt('/stats');
    await band('C-5 읽기 실패 띠');

    expectNoHangul('C-5 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-8 고객사 목록을 영어로', () => {
  test('B5-AC-05 C-8 목록(정상, 보관, 정지, 가입 요청 수): 한글이 없다', async () => {
    openInEnglish();
    state.companies = [
      { ...ACME_EN, pendingMembershipRequests: 2 },
      BETA_EN,
      company({ code: 'OLD', name: 'Old Trading', legalName: 'Old Trading Co.', status: 'ARCHIVED' }),
      company({ code: 'ZETA', name: 'Zeta Soft', legalName: 'Zeta Soft Ltd.', status: 'SUSPENDED' }),
    ];
    await open('/companies', 'Zeta Soft');

    expectNoHangul('C-8 목록');
    expectEnglishPage('C-8 목록');
  });

  test('B5-AC-05 C-8 빈 목록: 한글이 없다', async () => {
    openInEnglish();
    state.companies = [];
    renderAt('/companies');
    await heading1();
    await settle();

    expectNoHangul('C-8 빈 목록');
  });

  test('B5-AC-05 C-8 고객사 만들기 대화상자와 칸 오류(코드가 이미 있음): 한글이 없다', async () => {
    openInEnglish();
    on('post', '/api/console/companies', () => errors.companyCodeTaken());
    const user = await open('/companies', 'Beta Trading');
    await user.click(await control(/^(고객사 만들기|(create|new|add) company)/i));
    const box = await dialog();
    const inputs = Array.from(box.querySelectorAll<HTMLInputElement>('input[type="text"], input:not([type])'));
    expect(inputs.length, '대화상자의 글 칸').toBeGreaterThan(0);
    expectNoHangul('C-8 만들기 대화상자', box);

    await user.type(inputs[0] as HTMLInputElement, 'ACME');
    for (const input of inputs.slice(1)) await user.type(input, 'Acme Again');
    await user.click(await found(() => box.querySelector<HTMLButtonElement>('button[type="submit"]'), '대화상자의 보내기 단추'));
    await found(() => box.querySelector('[aria-invalid="true"]') ?? within(box).queryAllByRole('alert')[0], '코드가 이미 있다는 오류');

    expectNoHangul('C-8 칸 오류');
  });

  test('B5-AC-05 C-8 고객사를 만든 뒤 토스트: 한글이 없다', async () => {
    openInEnglish();
    const user = await open('/companies', 'Beta Trading');
    await user.click(await control(/^(고객사 만들기|(create|new|add) company)/i));
    const box = await dialog();
    const inputs = Array.from(box.querySelectorAll<HTMLInputElement>('input[type="text"], input:not([type])'));
    await user.type(inputs[0] as HTMLInputElement, 'GAMMA');
    for (const input of inputs.slice(1)) await user.type(input, 'Gamma Works');
    await user.click(await found(() => box.querySelector<HTMLButtonElement>('button[type="submit"]'), '대화상자의 보내기 단추'));
    await shows('Gamma Works');
    await settle();

    expectNoHangul('C-8 만든 뒤');
  });

  test('B5-AC-05 C-8 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    on('get', '/api/console/companies', () => errors.internal());
    renderAt('/companies');
    await band('C-8 읽기 실패 띠');

    expectNoHangul('C-8 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-9 고객사 상세를 영어로', () => {
  function fillAcme() {
    openInEnglish();
    state.companies = [{ ...ACME_EN, pendingMembershipRequests: 1 }, BETA_EN];
    state.customers = [
      memberEn(),
      memberEn({ accountId: 12, email: 'park@acme.test', name: 'Park Colleague', manager: true }),
      memberEn({ accountId: 13, email: 'choi@acme.test', name: 'Choi Invited', status: 'INVITED' }),
      memberEn({ accountId: 14, email: 'jung@acme.test', name: 'Jung Suspended', status: 'SUSPENDED' }),
    ];
    state.agents = [memberEn({ accountId: 2, email: AGENT_EN.email, name: AGENT_EN.name })];
    s6a.labels = {
      ACME: [label({ id: 61, name: 'Urgent', color: '#C0262D', issueCount: 3 }), label({ id: 62, name: 'Billing', color: '#245FB0', issueCount: 1234 })],
      BETA: [],
    };
    s10b.exports = {
      ACME: [
        running({ id: exportId(3), requestedBy: { accountId: 1, name: 'Morgan Admin' }, requestedAt: '2026-10-08T03:18:00Z' }),
        companyExport({ id: exportId(2), requestedBy: { accountId: 5, name: 'Pat Admin' } }),
        failed('DISK', { id: exportId(1), requestedBy: { accountId: 6, name: 'Han Admin' }, requestedAt: '2026-10-07T09:00:00Z' }),
      ],
      BETA: [],
    };
    pending.requests = [
      request({
        company: { code: 'ACME', name: 'Acme' },
        requestedBy: { accountId: 11, name: 'Kim Customer', email: 'kim@acme.test' },
        name: 'Park Typed',
        email: 'park@other.test',
        account: { accountId: 31, name: 'Park Real', role: 'CUSTOMER', status: 'ACTIVE', invitationCancelled: false },
      }),
    ];
  }

  test('B5-AC-05 C-9 구역이 모두 찬 상태(고객, 상담원, 가입 요청, 라벨, 내보내기): 한글이 없다', async () => {
    fillAcme();
    await open('/companies/ACME', 'Jung Suspended');
    await shows('Billing');
    await shows('Han Admin');
    await shows('Park Typed');

    expectNoHangul('C-9 찬 상태');
    expectEnglishPage('C-9 찬 상태');
  });

  test('B5-AC-05 C-9 구역이 빈 고객사: 한글이 없다', async () => {
    openInEnglish();
    state.customers = [];
    state.agents = [];
    s6a.labels = { ACME: [], BETA: [] };
    s10b.exports = { ACME: [], BETA: [] };
    await open('/companies/BETA', 'Beta Trading');

    expectNoHangul('C-9 빈 구역');
  });

  test('B5-AC-05 C-9 내보내기 대화상자: 한글이 없다', async () => {
    fillAcme();
    const user = await open('/companies/ACME', 'Han Admin');
    await user.click(await control(/^(내보내기|export)/i));
    const box = await dialog();

    expectNoHangul('C-9 내보내기 대화상자', box);
  });

  test('B5-AC-05 C-9 없는 고객사(404): 안내가 영어다', async () => {
    openInEnglish();
    renderAt('/companies/NOPE');
    await heading1();
    await settle();

    expectNoHangul('C-9 404');
  });
});

describe('B5 ④ 콘솔 C-10 구성원을 영어로', () => {
  function fillMembers() {
    openInEnglish();
    state.accounts = [
      accountEn(),
      accountEn({ id: 2, email: AGENT_EN.email, name: AGENT_EN.name, role: 'AGENT' }),
      accountEn({ id: 11, email: 'kim@acme.test', name: 'Kim Customer', role: 'CUSTOMER' }),
      accountEn({ id: 14, email: 'jung@acme.test', name: 'Jung Suspended', role: 'CUSTOMER', status: 'SUSPENDED' }),
      accountEn({ id: 21, email: 'park@acme.test', name: 'Park Invitee', role: 'CUSTOMER', status: 'INVITED' }),
      accountEn({ id: 22, email: 'oidc@popdesk.test', name: 'Olive Linked', role: 'AGENT', oidcLinked: true }),
    ];
    state.invitations = [invitationEn(), invitationEn({ accountId: 23, email: 'old@acme.test', name: 'Old Invitee', expired: true })];
  }

  test('B5-AC-05 C-10 목록(역할·상태 여럿, 초대, OIDC 연결): 한글이 없다', async () => {
    fillMembers();
    await open('/members', 'Olive Linked');

    expectNoHangul('C-10 목록');
    expectEnglishPage('C-10 목록');
  });

  test('B5-AC-05 C-10 검색 결과가 빔: 한글이 없다', async () => {
    openInEnglish();
    state.accounts = [];
    state.invitations = [];
    renderAt('/members');
    await heading1();
    await settle();

    expectNoHangul('C-10 빈 목록');
  });

  test('B5-AC-05 C-10 초대 대화상자: 한글이 없다', async () => {
    fillMembers();
    const user = await open('/members', 'Olive Linked');
    await user.click(await control(/^(초대|invite)/i));
    const box = await dialog();

    expectNoHangul('C-10 초대 대화상자', box);
  });

  test('B5-AC-05 C-10 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    route('get', '/api/console/accounts', () => errors.internal());
    renderAt('/members');
    await band('C-10 읽기 실패 띠');

    expectNoHangul('C-10 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-11 휴지통을 영어로', () => {
  const TRASH_EN = [
    trashed({ key: 'ACME-12', title: 'Payment shows twice', companyName: 'Acme', deletedBy: ADMIN_PERSON_EN }),
    trashed({ key: 'BETA-5', title: 'Receipt mail never arrives', companyCode: 'BETA', companyName: 'Beta Trading', status: 'RESOLVED', deletedBy: null }),
  ];

  test('B5-AC-05 C-11 목록(관리자가 지운 것, 시스템이 지운 것): 한글이 없다', async () => {
    openInEnglish();
    s7a.trash = TRASH_EN;
    await open('/trash', 'Receipt mail never arrives');

    expectNoHangul('C-11 목록');
    expectEnglishPage('C-11 목록');
  });

  test('B5-AC-05 C-11 빈 휴지통: 한글이 없다', async () => {
    openInEnglish();
    s7a.trash = [];
    renderAt('/trash');
    await heading1();
    await settle();

    expectNoHangul('C-11 빈 휴지통');
  });

  test('B5-AC-05 C-11 영구 삭제 확인 대화상자: 한글이 없다', async () => {
    openInEnglish();
    s7a.trash = TRASH_EN;
    const user = await open('/trash', 'Payment shows twice');
    const row = await found(() => screen.queryAllByRole('row').find((r) => text(r).includes('Payment shows twice')), '휴지통 줄');
    await user.click(await control(/(영구 삭제|delete (permanently|forever)|purge)/i, row));
    const box = await dialog();

    expectNoHangul('C-11 영구 삭제 대화상자', box);
  });

  test('B5-AC-05 C-11 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    route('get', '/api/console/trash', () => errors.internal());
    renderAt('/trash');
    await band('C-11 읽기 실패 띠');

    expectNoHangul('C-11 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-12 감사 로그를 영어로', () => {
  const ENTRIES_EN = [
    entry({ id: 7741, action: 'COMPANY_CREATED', actor: ADMIN_ACTOR_EN, target: { type: 'COMPANY', code: 'ACME' }, changes: { code: 'ACME', name: 'Acme', legalName: 'Acme Inc.', status: 'ACTIVE' } }),
    entry({ id: 7740, action: 'AUDIT_EXPORTED', actor: ADMIN_ACTOR_EN, target: { type: 'AUDIT_LOG' }, changes: { format: 'CSV', count: 1234, filter: {} } }),
    entry({ id: 7739, action: 'ACCOUNT_SUSPENDED', actor: ADMIN_ACTOR_EN }),
    entry({ id: 7738, action: 'ACCOUNT_ROLE_CHANGED', actor: ADMIN_ACTOR_EN, changes: { role: { from: 'CUSTOMER', to: 'AGENT' } } }),
    entry({ id: 7737, action: 'SIGN_IN_SUCCEEDED', actor: AGENT_ACTOR_EN, target: { type: 'ACCOUNT', accountId: 2 }, changes: null }),
    entry({ id: 7736, action: 'SIGN_IN_FAILED', actor: SYSTEM_ACTOR, target: null, changes: { email: 'x***@popdesk.test' } }),
    entry({ id: 7735, action: 'BACKUP_CREATED', actor: SYSTEM_ACTOR, target: { type: 'DATABASE' }, changes: { file: 'popdesk-20261007.dump' } }),
  ];

  test('B5-AC-05 C-12 목록(감사 종류 여럿, 사람과 시스템): 한글이 없다', async () => {
    openInEnglish();
    audit.entries = ENTRIES_EN;
    await open('/audit-log', 'Lee Agent');

    expectNoHangul('C-12 목록');
    expectEnglishPage('C-12 목록');
  });

  test('B5-AC-05 C-12 거른 결과가 빔: 한글이 없다', async () => {
    openInEnglish();
    audit.entries = [];
    renderAt('/audit-log');
    await heading1();
    await settle();

    expectNoHangul('C-12 빈 목록');
  });

  test('B5-AC-05 C-12 무결성 확인 결과: 한글이 없다', async () => {
    openInEnglish();
    audit.entries = ENTRIES_EN;
    const user = await open('/audit-log', 'Lee Agent');
    await user.click(await control(/무결성|verif/i));
    await eventually(() => {
      expect(text(document.body), '확인한 수 57').toMatch(/57/);
    });

    expectNoHangul('C-12 무결성 확인 결과');
  });

  test('B5-AC-05 C-12 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    route('get', AUDIT, () => errors.internal());
    renderAt('/audit-log');
    await band('C-12 읽기 실패 띠');

    expectNoHangul('C-12 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-13 SLA 정책을 영어로', () => {
  test.each([
    ['목표와 업무 시간이 있는 정책', () => onPolicy({ changedBy: ADMIN_PERSON_EN })],
    ['한 번도 정하지 않은 정책', () => offPolicy()],
  ])('B5-AC-05 C-13 %s: 한글이 없다', async (what, policy) => {
    openInEnglish();
    s8a.policy = policy();
    renderAt('/sla');
    await heading1();
    await found(() => screen.queryAllByRole('table')[0], 'C-13 목표 표');
    await settle();

    expectNoHangul(`C-13 ${what}`);
    expectEnglishPage(`C-13 ${what}`);
  });

  test('B5-AC-05 C-13 칸 오류(범위 밖의 분): 칸 오류가 영어다', async () => {
    openInEnglish();
    s8a.policy = onPolicy({ changedBy: ADMIN_PERSON_EN });
    const { user } = renderAt('/sla');
    const table = await found(() => screen.queryAllByRole('table')[0], 'C-13 목표 표');
    const minutes = await found(() => table.querySelector<HTMLInputElement>('input'), '목표 칸');
    await user.clear(minutes);
    await user.type(minutes, '0');
    await user.click(await control(/^(저장|save)/i));
    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');

    expectNoHangul('C-13 칸 오류');
  });

  test('B5-AC-05 C-13 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    route('get', SLA_POLICY, () => errors.internal());
    renderAt('/sla');
    await band('C-13 읽기 실패 띠');

    expectNoHangul('C-13 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-14 브랜딩을 영어로', () => {
  async function openBranding(b: Branding): Promise<User> {
    openInEnglish();
    s9.branding = b;
    s9.public = publicOf(b);
    const { user } = renderAt('/branding');
    await heading1();
    await eventually(() => {
      expect(
        Array.from(document.querySelectorAll<HTMLInputElement>('input')).some((i) => i.value === b.displayName),
        '읽은 표시 이름',
      ).toBe(true);
    });
    await settle();
    return user;
  }

  test('B5-AC-05 C-14 처음(기본 값, 로고 없음): 한글이 없다', async () => {
    await openBranding(defaultBranding());

    expectNoHangul('C-14 처음');
    expectEnglishPage('C-14 처음');
  });

  test('B5-AC-05 C-14 로고 둘과 이름이 있음: 한글이 없다', async () => {
    await openBranding(defaultBranding({ version: 3, displayName: 'Acme Support', logos: { light: WIDE_LOGO, dark: SQUARE_LOGO } }));

    expectNoHangul('C-14 로고 있음');
  });

  test('B5-AC-05 C-14 표시 이름을 비우고 저장(칸 오류): 한글이 없다', async () => {
    const user = await openBranding(defaultBranding({ version: 2, displayName: 'Acme Support' }));
    const name = await found(() => Array.from(document.querySelectorAll<HTMLInputElement>('input')).find((i) => i.value === 'Acme Support'), '표시 이름 칸');
    await user.clear(name);
    await user.click(await control(/^(저장|save)/i));
    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');

    expectNoHangul('C-14 칸 오류');
  });

  test('B5-AC-05 C-14 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    route('get', BRANDING, () => errors.internal());
    renderAt('/branding');
    await band('C-14 읽기 실패 띠');

    expectNoHangul('C-14 읽기 실패');
  });
});

describe('B5 ④ 콘솔 C-18 시스템 상태를 영어로', () => {
  test('B5-AC-05 C-18 모두 켜지고 실패가 있는 상태(채널 실패, 실패한 전달, 정리 작업, OIDC, 메일 받기): 한글이 없다', async () => {
    openInEnglish();
    state.status = status({
      mail: channel({ failed: 3, queued: 2 }),
      webhook: channel({ failed: 1 }),
      auditForward: channel({ failed: 1234 }),
      cleanupJobs: [
        cleanupJob({ name: 'attachment-cleanup', deleted: 37 }),
        cleanupJob({ name: 'session-cleanup', lastRunAt: null, deleted: null }),
      ],
      oidc: oidcOn({ apps: ['CONSOLE', 'PORTAL'], lastSuccessAt: '2026-10-08T03:00:00Z', providerFailures: 2, lastFailure: { kind: 'TOKEN', at: '2026-10-08T03:10:00Z' } }),
      mailIn: mailInOn({ lastSuccessAt: '2026-10-08T03:00:00Z', roundFailures: 2, lastFailure: { kind: 'AUTH', at: '2026-10-08T03:10:00Z' }, retrying: 3, quarantined90d: 1 }),
    });
    state.failed = [
      failedDelivery({ id: 1, channel: 'MAIL', lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: 11 }),
      failedDelivery({ id: 2, channel: 'WEBHOOK', lastErrorKind: 'TIMEOUT', recipientAccountId: null }),
    ];
    await open('/system', 'attachment-cleanup');

    expectNoHangul('C-18 모두 켜짐');
    expectEnglishPage('C-18 모두 켜짐');
  });

  test('B5-AC-05 C-18 샌드박스(받는 주소 없음), 아무 실패 없음: 한글이 없다', async () => {
    openInEnglish();
    const off = channel({ enabled: false, lastSuccessAt: null });
    state.status = status({ sandbox: true, sandboxMailToSet: false, mail: off, webhook: off, auditForward: off, mailIn: { ...mailInOn(), enabled: false, offReason: 'SANDBOX' } });
    state.failed = [];
    renderAt('/system');
    await heading1();
    await settle();

    expectNoHangul('C-18 샌드박스');
  });

  test('B5-AC-05 C-18 시험 메일 결과: 한글이 없다', async () => {
    openInEnglish();
    state.status = status();
    const { user } = renderAt('/system');
    await heading1();
    await user.click(await control(/^(시험 메일|send (a )?test)/i));
    await eventually(() => {
      expect(text(document.body), 'SMTP 서버의 답').toContain('250 2.0.0 Ok');
    });

    expectNoHangul('C-18 시험 메일 결과');
  });

  test('B5-AC-05 C-18 읽기 실패(500): 오류 띠가 영어다', async () => {
    openInEnglish();
    on('get', '/api/console/system/status', () => errors.internal());
    renderAt('/system');
    await band('C-18 읽기 실패 띠');

    expectNoHangul('C-18 읽기 실패');
  });
});
