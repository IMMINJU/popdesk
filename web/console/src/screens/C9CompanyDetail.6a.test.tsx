// 6a 묶음 screens.md 4절 C-9 고객사 상세의 "라벨" 구역(관리자): 이름순 목록(칩, "문의 {n}건", 고치기,
// 지우기), 빈 상태, 만들기·고치기 대화상자(이름 글자 수, 색 견본 라디오 그룹, #RRGGBB 입력, 미리 보기,
// 409 문구), 붙은 수가 든 지우기 확인, 200개 한도, 보관한 고객사. 다른 고객사로 옮긴 뒤 온 답은 버리고,
// 대화상자가 닫힌 뒤 온 실패는 버리고 성공은 목록을 다시 그린다(S6A-AC-83, S6A-AC-86).
import { HttpResponse, http } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  company,
  dialog,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  on,
  openDialog,
  renderAt,
  screen,
  sent,
  server,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, closed, confirmButton, region, rowAction, rowActions, settle } from '../__tests__/console1a';
import {
  ACME_LABELS,
  BETA_LABELS,
  BILLING,
  MOBILE,
  SWATCHES,
  URGENT,
  bodyOf,
  chipIn,
  companyLabelPath,
  contract,
  label,
  labelLine,
  labelLineNames,
  labelRegion,
  manyLabels,
  rejectedWith,
  rgbText,
  s6a,
} from '../__tests__/console6a';

const URL_ACME = '/companies/ACME';
const EMPTY_TEXT = '아직 라벨이 없습니다.';
const TAKEN_TEXT = '이 고객사에 같은 이름의 라벨이 있습니다.';
const LIMIT_TEXT = '라벨은 고객사마다 200개까지 만듭니다.';
const DELETE_TEXT = "라벨 '긴급'을 지웁니다. 붙어 있는 문의 3건에서도 떨어집니다. 이력에는 남지 않습니다.";

type User = ReturnType<typeof renderAt>['user'];

const makeButton = () => {
  const r = labelRegion();
  return r ? within(r).queryByRole('button', { name: '라벨 만들기' }) : null;
};

/** The line of `name` in the label region, waited for. */
const lineOf = (name: string) => found(() => labelLine(name), `라벨 구역의 "${name}" 줄`);

const nameField = (box: HTMLElement) => within(box).getByLabelText<HTMLInputElement>('이름');
const swatchGroup = (box: HTMLElement) => within(box).queryByRole('radiogroup');
/** The `#RRGGBB` input: the text box of the dialog that is not 이름. */
const hexField = (box: HTMLElement) => within(box).queryAllByRole<HTMLInputElement>('textbox').find((i) => i !== nameField(box)) ?? null;

async function openCreate(user: User): Promise<HTMLElement> {
  await user.click(await found(makeButton, '"라벨 만들기"'));
  return dialog();
}

/** Goes to another company as a person would: the menu "고객사", then its link in the list. */
async function goToCompany(user: User, name: RegExp, code: string): Promise<void> {
  await user.click(await found(() => screen.queryAllByRole('link', { name: '고객사' }).find((a) => a.closest('nav') !== null), '메뉴 "고객사"'));
  await user.click(await found(() => screen.queryByRole('link', { name }), `${code} 링크`));
  await eventually(() => {
    expect(window.location.pathname).toBe(`/companies/${code}`);
  });
}

/** The texts of the `role="alert"` bands that say something. */
const alertsShown = () => screen.queryAllByRole('alert').map(text).filter((t) => t !== '');

/** The three label dialogs: how to open one, fill it, send it, and the list after its success. */
const LATE: {
  what: string;
  method: 'post' | 'patch' | 'delete';
  path: string;
  open: (user: User) => Promise<HTMLElement>;
  fill: (user: User, box: HTMLElement) => Promise<void>;
  go: (box: HTMLElement) => HTMLElement;
  after: { shown: string[]; gone: string[] };
}[] = [
  {
    what: '만들기',
    method: 'post',
    path: ACME_LABELS,
    open: openCreate,
    fill: (user, box) => user.type(nameField(box), '환불'),
    go: confirmButton,
    after: { shown: ['환불', '긴급'], gone: [] },
  },
  {
    what: '고치기',
    method: 'patch',
    path: companyLabelPath(URGENT.id),
    open: async (user) => {
      await user.click(await rowAction(user, await lineOf('긴급'), '고치기'));
      return dialog();
    },
    fill: async (user, box) => {
      await user.clear(nameField(box));
      await user.type(nameField(box), '아주 급함');
    },
    go: confirmButton,
    after: { shown: ['아주 급함'], gone: ['긴급'] },
  },
  {
    what: '지우기',
    method: 'delete',
    path: companyLabelPath(URGENT.id),
    open: async (user) => {
      await user.click(await rowAction(user, await lineOf('긴급'), '지우기'));
      return dialog();
    },
    fill: () => Promise.resolve(),
    go: (box) => within(box).getByRole('button', { name: '지우기' }),
    after: { shown: ['결제'], gone: ['긴급'] },
  },
];

describe('C-9 고객사 상세 라벨 구역 (6a 묶음)', () => {
  test('S6A-AC-83 C-9 라벨 목록: "배정 상담원" 다음에 "라벨" 구역이 있고 줄마다 이름, "문의 {n}건", "고치기", "지우기"다. 이름순이다', async () => {
    const { user } = renderAt(URL_ACME);

    const labels = await found(labelRegion, '구역 "라벨"');
    const agents = await region(/배정 상담원/);
    expect(agents.compareDocumentPosition(labels) & Node.DOCUMENT_POSITION_FOLLOWING, '"배정 상담원" 다음').toBeTruthy();
    await sent('GET', ACME_LABELS);
    await lineOf('긴급');
    expect(labelLineNames(['결제', '긴급', '모바일'])).toEqual(['결제', '긴급', '모바일']);
    expect(text(await lineOf('긴급'))).toContain('문의 3건');
    expect(text(await lineOf('결제'))).toContain('문의 1건');
    expect(text(await lineOf('모바일'))).toContain('문의 0건');
    const actions = await rowActions(user, await lineOf('긴급'));
    expect(actions).toEqual(expect.arrayContaining(['고치기', '지우기']));
  });

  test('S6A-AC-83 C-9 빈 상태: "아직 라벨이 없습니다."와 "라벨 만들기" 단추', async () => {
    s6a.labels = { ...s6a.labels, ACME: [] };
    renderAt(URL_ACME);

    const labels = await found(labelRegion, '구역 "라벨"');
    await found(() => within(labels).queryByText(EMPTY_TEXT), `"${EMPTY_TEXT}"`);
    expect(makeButton(), '"라벨 만들기"').not.toBeNull();
  });

  test('S6A-AC-83 C-9 만들기 대화상자: 이름 글자 수, 견본 여덟의 라디오 그룹(기본 회색), #808080 입력, 미리 보기 칩이 있다. axe 위반이 없다', async () => {
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);

    await user.type(nameField(box), '환불');
    expect(text(box), '이름 글자 수').toMatch(/2\s*\/\s*30/);
    const group = swatchGroup(box);
    expect(group, '색 견본 radiogroup').not.toBeNull();
    const radios = within(group as HTMLElement).queryAllByRole<HTMLInputElement>('radio');
    expect(radios).toHaveLength(SWATCHES.length);
    SWATCHES.forEach(([name], i) => {
      expect(radios[i]).toHaveAccessibleName(name);
    });
    expect(within(group as HTMLElement).getByRole('radio', { name: '회색' })).toBeChecked();
    expect(hexField(box)?.value, '#RRGGBB 입력').toBe('#808080');
    expect(within(box).queryAllByText('환불', { exact: true }).length, '미리 보기 칩').toBeGreaterThan(0);
    await expectNoAxeViolations();
  });

  test('S6A-AC-83 C-9 만들기: 견본을 고르면 그 색으로 POST하고, 닫힌 뒤 목록에 새 줄이 있다', async () => {
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);

    await user.type(nameField(box), '환불');
    await user.click(within(swatchGroup(box) as HTMLElement).getByRole('radio', { name: '초록' }));
    await user.click(confirmButton(box));

    await sent('POST', ACME_LABELS);
    expect(await bodyOf('POST', ACME_LABELS)).toEqual({ name: '환불', color: '#1F7A3A' });
    await closed();
    await lineOf('환불');
  });

  test('S6A-AC-83 C-9 만들기: #RRGGBB 입력에 적은 색으로 POST한다', async () => {
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);

    await user.type(nameField(box), '환불');
    const hex = hexField(box);
    expect(hex, '#RRGGBB 입력').not.toBeNull();
    await user.clear(hex as HTMLInputElement);
    await user.type(hex as HTMLInputElement, '#0F766E');
    await user.click(confirmButton(box));

    await sent('POST', ACME_LABELS);
    expect((await bodyOf('POST', ACME_LABELS))['color']).toBe('#0F766E');
  });

  test('S6A-AC-83 C-9 만들기 409: 이름 칸 아래 "이 고객사에 같은 이름의 라벨이 있습니다."이고 대화상자가 열려 있다', async () => {
    on('post', ACME_LABELS, () => rejectedWith('createLabel', 'LABEL_NAME_TAKEN', 409));
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);

    await user.type(nameField(box), '긴급');
    await user.click(confirmButton(box));

    await sent('POST', ACME_LABELS);
    await expectFieldError(nameField(box), TAKEN_TEXT);
    expect(openDialog(), '열린 대화상자').not.toBeNull();
  });

  test('S6A-AC-83 C-9 고치기: 지금 이름과 색으로 열리고, 바꾼 이름이 그 라벨 ID로 PATCH된다', async () => {
    const { user } = renderAt(URL_ACME);
    await user.click(await rowAction(user, await lineOf('긴급'), '고치기'));
    const box = await dialog();

    expect(nameField(box)).toHaveValue('긴급');
    expect(within(swatchGroup(box) as HTMLElement).getByRole('radio', { name: '빨강' })).toBeChecked();
    await user.clear(nameField(box));
    await user.type(nameField(box), '아주 급함');
    await user.click(confirmButton(box));

    await sent('PATCH', companyLabelPath(URGENT.id));
    expect((await bodyOf('PATCH', companyLabelPath(URGENT.id)))['name']).toBe('아주 급함');
    await closed();
    await lineOf('아주 급함');
  });

  test('S6A-AC-83 C-9 지우기: 붙은 문의 수가 든 문장으로 한 번 더 묻고, "지우기"가 DELETE를 보내 줄이 사라진다', async () => {
    const { user } = renderAt(URL_ACME);
    await user.click(await rowAction(user, await lineOf('긴급'), '지우기'));
    const box = await dialog();

    expect(text(box)).toContain(DELETE_TEXT);
    await user.click(within(box).getByRole('button', { name: '지우기' }));

    await sent('DELETE', companyLabelPath(URGENT.id));
    await closed();
    await eventually(() => {
      expect(labelLine('긴급'), '지운 줄').toBeNull();
    });
    expect(labelLine('결제'), '남은 줄').not.toBeNull();
  });

  test('S6A-AC-83 C-9 200개: "라벨 만들기"가 막히고 "라벨은 고객사마다 200개까지 만듭니다."가 보인다', async () => {
    s6a.labels = { ...s6a.labels, ACME: manyLabels(200) };
    renderAt(URL_ACME);

    await lineOf('라벨01');
    const make = await found(makeButton, '"라벨 만들기"');
    expect(isBlocked(make)).toBe(true);
    await found(() => within(labelRegion() as HTMLElement).queryByText(LIMIT_TEXT), `"${LIMIT_TEXT}"`);
  });

  test('S6A-AC-83 C-9 보관한 고객사: "라벨 만들기"가 없고 줄의 "고치기", "지우기"는 있다', async () => {
    state.companies = [company({ status: 'ARCHIVED' })];
    const { user } = renderAt(URL_ACME);

    const line = await lineOf('긴급');
    expect(makeButton(), '보관한 고객사의 "라벨 만들기"').toBeNull();
    expect(await rowActions(user, line)).toEqual(expect.arrayContaining(['고치기', '지우기']));
  });

  test('S6A-AC-86 C-9 다른 고객사로 옮긴 뒤 온 만들기 답은 버린다: 새 고객사의 목록에 그 라벨이 없고 대화상자도 오류도 없다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('post', ACME_LABELS, async () => {
      await release.opened;
      return HttpResponse.json(contract('createLabel', 201, label({ id: 95, name: '환불', color: '#808080', issueCount: 0 })), { status: 201 });
    });
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);
    await user.type(nameField(box), '환불');
    await user.click(confirmButton(box));
    await sent('POST', ACME_LABELS);

    await goToCompany(user, /베타상사/, 'BETA');
    await sent('GET', BETA_LABELS);
    await lineOf('베타전용');
    const done = answered('POST', ACME_LABELS);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/companies/BETA');
    expect(labelLine('환불'), 'BETA 목록의 ACME 라벨').toBeNull();
    expect(openDialog(), '대화상자').toBeNull();
    expect(screen.queryAllByRole('alert').map(text).join(' ')).not.toContain('라벨');
  });

  test.each([
    { what: '409', answer: () => rejectedWith('createLabel', 'LABEL_NAME_TAKEN', 409) },
    { what: '500', answer: () => errors.internal() },
  ])('S6A-AC-86 C-9 다른 고객사로 옮긴 뒤 온 만들기 실패($what)는 버린다: 새 고객사에 오류 띠도 대화상자도 없다', async ({ answer }) => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('post', ACME_LABELS, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);
    await user.type(nameField(box), '긴급');
    await user.click(confirmButton(box));
    await sent('POST', ACME_LABELS);

    await goToCompany(user, /베타상사/, 'BETA');
    await sent('GET', BETA_LABELS);
    await lineOf('베타전용');
    const done = answered('POST', ACME_LABELS);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/companies/BETA');
    expect(openDialog(), '대화상자').toBeNull();
    expect(alertsShown(), '오류 띠').toEqual([]);
    const again = await openCreate(user);
    expect(within(again).queryByText(TAKEN_TEXT), 'BETA 대화상자의 409 문구').toBeNull();
    expect(within(again).queryAllByRole('alert').filter((el) => text(el) !== ''), 'BETA 대화상자의 오류').toEqual([]);
  });

  test.each(LATE)('S6A-AC-86 C-9 $what 대화상자를 닫은 뒤 온 성공은 목록을 서버의 값으로 다시 그린다', async (late) => {
    const release = gate();
    // The gated handler returns nothing, so the request goes on to the fake server, which changes the list.
    server.use(
      http[late.method](`*${late.path}`, async () => {
        await release.opened;
      }),
    );
    const { user } = renderAt(URL_ACME);
    const box = await late.open(user);
    await late.fill(user, box);
    await user.click(late.go(box));
    await sent(late.method.toUpperCase(), late.path);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered(late.method.toUpperCase(), late.path);
    release.open();
    await done;
    await settle();

    await eventually(() => {
      for (const name of late.after.shown) expect(labelLine(name), `"${name}" 줄`).not.toBeNull();
      for (const name of late.after.gone) expect(labelLine(name), `"${name}" 줄`).toBeNull();
    });
    expect(openDialog(), '닫힌 대화상자').toBeNull();
  });

  test.each(LATE.filter((late) => late.method !== 'post'))(
    'S6A-AC-86 C-9 $what 대화상자를 닫은 뒤 온 실패(500)는 버린다: 오류 띠가 없고 다시 연 대화상자에 오류가 없다',
    async (late) => {
      const release = gate();
      server.use(
        http[late.method](`*${late.path}`, async () => {
          await release.opened;
          return errors.internal();
        }),
      );
      const { user } = renderAt(URL_ACME);
      const box = await late.open(user);
      await late.fill(user, box);
      await user.click(late.go(box));
      await sent(late.method.toUpperCase(), late.path);

      await user.keyboard('{Escape}');
      await closed();
      const done = answered(late.method.toUpperCase(), late.path);
      release.open();
      await done;
      await settle();

      expect(alertsShown(), '닫힌 뒤 온 실패의 오류 띠').toEqual([]);
      expect(labelLine('긴급'), '그대로인 줄').not.toBeNull();
      const again = await late.open(user);
      expect(within(again).queryAllByRole('alert').filter((el) => text(el) !== ''), '다시 연 대화상자의 오류').toEqual([]);
      expect(isBlocked(late.go(again)), '다시 연 대화상자의 단추').toBe(false);
    },
  );

  test('S6A-AC-83 C-9 목록 줄의 색 견본: 줄마다 그 라벨의 이름이 든 칩이 있고 바탕이 라벨 색이다', async () => {
    renderAt(URL_ACME);

    for (const l of [URGENT, BILLING, MOBILE]) {
      const line = await lineOf(l.name);
      const chip = await found(() => chipIn(line, l.name), `"${l.name}" 줄의 칩`);
      expect(chip.style.backgroundColor, `"${l.name}" 칩의 바탕`).toBe(rgbText(l.color));
    }
  });

  test('S6A-AC-86 C-9 닫은 뒤 온 409는 버린다: 다시 연 대화상자에 오류가 없다', async () => {
    const release = gate();
    on('post', ACME_LABELS, async () => {
      await release.opened;
      return rejectedWith('createLabel', 'LABEL_NAME_TAKEN', 409);
    });
    const { user } = renderAt(URL_ACME);
    const box = await openCreate(user);
    await user.type(nameField(box), '긴급');
    await user.click(confirmButton(box));
    await sent('POST', ACME_LABELS);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('POST', ACME_LABELS);
    release.open();
    await done;
    await settle();

    expect(screen.queryByText(TAKEN_TEXT), '닫힌 뒤 온 409').toBeNull();
    const again = await openCreate(user);
    expect(within(again).queryByText(TAKEN_TEXT), '다시 연 대화상자의 409 문구').toBeNull();
  });
});
