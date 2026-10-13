// screens.md 3절 P-4 문의 등록. 문의를 먼저 만들고 첨부를 하나씩 따로 올린다(7절).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  MB,
  acceptUpload,
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  field,
  file,
  found,
  gate,
  heading1,
  issueBody,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  text,
  uploadName,
  uploadedNames,
  type Part,
} from '../__tests__/portal';

type User = ReturnType<typeof renderAt>['user'];

const title = () => field(/^제목/, 'input');
const content = () => field<HTMLTextAreaElement>(/^내용/, 'textarea');
const attachInput = () => field(/첨부/, 'input[type="file"]');

async function fillIssue(user: User) {
  await user.type(await title(), '프린터가 안 됩니다');
  await user.type(await content(), '사무실 프린터가 응답하지 않습니다.');
}

describe('P-4 문의 등록', () => {
  test('P-4 처음: 제목, 내용, 우선순위(기본 보통), 비공개(기본 공개, 설명), 첨부, "올리기"·"취소"', async () => {
    renderAt('/issues/new');

    await title();
    await content();
    const priority = await found(() => screen.queryByRole('radiogroup', { name: /우선순위/ }), '우선순위 radiogroup');
    for (const word of ['낮음', '보통', '높음', '긴급']) {
      expect(screen.getByRole('radio', { name: new RegExp(word) }), `우선순위 "${word}"`).toBeInTheDocument();
    }
    expect(screen.getByRole('radio', { name: /보통/ })).toBeChecked();
    expect(priority).toBeInTheDocument();
    const privateBox = await found(() => screen.queryByRole('checkbox', { name: /비공개/ }), '비공개 체크');
    expect(privateBox).not.toBeChecked();
    expect(pageText()).toContain('같은 회사 동료에게 보이지 않습니다');
    await attachInput();
    await button('올리기');
    await button('취소');
  });

  test('P-4 처음: 소속이 하나면 그 고객사를 고른 채로 두고 그대로 보낸다', async () => {
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    expect(pageText()).toContain(ACME.name);
    await user.click(await button('올리기'));

    const [create] = await sent('POST', '/api/portal/issues');
    expect(await issueBody(create)).toMatchObject({
      companyCode: ACME.code,
      title: '프린터가 안 됩니다',
      content: '사무실 프린터가 응답하지 않습니다.',
    });
  });

  test('P-4 처음: 소속 고객사가 둘이면 고객사 Select에서 고른 값을 보낸다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt('/issues/new');
    const company = await field<HTMLSelectElement>(/^고객사/, 'select');
    await user.selectOptions(company, BETA.code);
    await fillIssue(user);
    await user.click(await button('올리기'));

    const [create] = await sent('POST', '/api/portal/issues');
    expect(await issueBody(create)).toMatchObject({ companyCode: BETA.code });
  });

  test('P-4 우선순위·비공개: 고른 우선순위와 비공개를 보낸다', async () => {
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await found(() => screen.queryByRole('radio', { name: /높음/ }), '높음'));
    await user.click(await found(() => screen.queryByRole('checkbox', { name: /비공개/ }), '비공개 체크'));
    await user.click(await button('올리기'));

    const [create] = await sent('POST', '/api/portal/issues');
    expect(await issueBody(create)).toMatchObject({ priority: 'HIGH', private: true });
  });

  test('P-4 성공: P-5로 가고 토스트 "문의를 올렸습니다(ACME-8)"', async () => {
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    await statusWith('문의를 올렸습니다(ACME-8)');
  });

  test('P-4 첨부 고름: 파일마다 이름, 크기, 지우기 버튼. 지우면 올리지 않는다', async () => {
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [file('error.log', 3 * 1024), file('screen.png', 2 * MB, 'image/png')]);

    await eventually(() => {
      expect(pageText()).toContain('error.log');
      expect(pageText()).toContain('screen.png');
    });
    const removes = screen.queryAllByRole('button', { name: /지우기|빼기/ });
    expect(removes, '파일 줄의 지우기 버튼').toHaveLength(2);
    await user.click(screen.getAllByRole('button', { name: /지우기|빼기/ })[0] as HTMLElement);
    await eventually(() => {
      expect(pageText()).not.toContain('error.log');
    });

    await fillIssue(user);
    await user.click(await button('올리기'));
    await atPath('/issues/ACME-8');
    expect(await uploadedNames()).toEqual(['screen.png']);
  });

  test('P-4 저장 중: 문의를 먼저 만들고 첨부를 하나씩 따로 올린다, 파일 줄마다 진행 막대', async () => {
    const release = gate();
    on('post', '/api/portal/issues/:key/attachments', async ({ request }) => {
      await release.opened;
      return acceptUpload(request);
    });
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [file('a.log'), file('b.log')]);
    await fillIssue(user);
    await user.click(await button('올리기'));

    await sent('POST', '/api/portal/issues');
    await sent('POST', /\/api\/portal\/issues\/ACME-8\/attachments$/);
    await found(() => screen.queryAllByRole('progressbar')[0], '파일 줄의 진행 막대');
    const create = callsTo('POST', '/api/portal/issues')[0];
    const createBody = await create?.body;
    const filesInCreate = Array.isArray(createBody) ? (createBody as Part[]).filter((p) => p.filename !== null) : [];
    expect(filesInCreate, '문의 등록 요청에 파일을 싣지 않는다').toEqual([]);

    release.open();
    await atPath('/issues/ACME-8');
    expect((await uploadedNames()).sort()).toEqual(['a.log', 'b.log']);
  });

  test('P-4 첨부 고름: 10MB 넘는 파일, 빈 파일, 이름 255자 넘음은 고를 때 막고 그 줄에 이유를 보이며 올리지 않는다(ATT-2)', async () => {
    const longName = `${'가'.repeat(252)}.txt`;
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [
      file('ok.txt', 1024),
      file('huge.zip', 10 * MB + 1, 'application/zip'),
      file('empty.txt', 0),
      file(longName, 10),
    ]);

    await eventually(() => {
      expect(pageText()).toContain('huge.zip');
      expect(pageText()).toContain('empty.txt');
    });
    await fillIssue(user);
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    expect(await uploadedNames()).toEqual(['ok.txt']);
  });

  test('P-4 첨부 고름: 합이 50MB를 넘게 되는 파일은 막는다(ATT-2)', async () => {
    const { user } = renderAt('/issues/new');
    await user.upload(
      await attachInput(),
      ['1', '2', '3', '4', '5', '6'].map((n) => file(`part${n}.bin`, 9 * MB, 'application/octet-stream')),
    );

    await eventually(() => {
      expect(pageText()).toContain('part6.bin');
    });
    await fillIssue(user);
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    expect((await uploadedNames()).sort()).toEqual(['part1.bin', 'part2.bin', 'part3.bin', 'part4.bin', 'part5.bin']);
  });

  test('S0-AC-87 P-4 첨부 일부 실패: 413(앞단 프록시)을 받아도 P-5로 가고 경고 띠에 파일 이름과 "파일이 서버 앞단의 크기 상한에 걸렸습니다"', async () => {
    on('post', '/api/portal/issues/:key/attachments', async ({ request }) => {
      if ((await uploadName(request)) === 'video.mp4') return errors.proxyPayloadTooLarge();
      return acceptUpload(request);
    });
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [file('notes.txt'), file('video.mp4', 9 * MB, 'video/mp4')]);
    await fillIssue(user);
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    const band = await alertWith('첨부 1개를 올리지 못했습니다.');
    expect(text(band)).toContain('video.mp4');
    expect(text(band)).toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    expect(text(band)).not.toContain('서버에 연결하지 못했습니다');
  });

  test('S0-AC-87 P-4 첨부 일부 실패: 413이 아닌 실패(400 한도, 5xx)에는 크기 상한 안내를 내지 않는다', async () => {
    on('post', '/api/portal/issues/:key/attachments', async ({ request }) => {
      const name = await uploadName(request);
      if (name === 'big.iso') {
        return HttpResponse.json({ code: 'ISSUE_ATTACHMENTS_TOO_LARGE', message: 'Over 50 MB.' }, { status: 400 });
      }
      if (name === 'trace.log') return errors.internal();
      return acceptUpload(request);
    });
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [file('notes.txt'), file('big.iso', 9 * MB), file('trace.log')]);
    await fillIssue(user);
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    const band = await alertWith('첨부 2개를 올리지 못했습니다.');
    expect(text(band)).toContain('big.iso');
    expect(text(band)).toContain('trace.log');
    expect(text(band), '413이 아닌 실패에 크기 상한 안내').not.toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
  });

  test('P-4 400 고객사 ACTIVE 아님: "이 고객사에는 지금 문의를 올릴 수 없습니다." 띠, 고객사 칸에 포커스', async () => {
    state.companies = [ACME, BETA];
    on('post', '/api/portal/issues', () =>
      HttpResponse.json({ code: 'COMPANY_NOT_ACTIVE', message: 'The company is not active.' }, { status: 400 }),
    );
    const { user } = renderAt('/issues/new');
    const company = await field<HTMLSelectElement>(/^고객사/, 'select');
    await user.selectOptions(company, BETA.code);
    await fillIssue(user);
    await user.click(await button('올리기'));

    await alertWith('이 고객사에는 지금 문의를 올릴 수 없습니다.');
    await eventually(() => {
      expect(document.activeElement).toBe(company);
    });
    expect(await title()).toHaveValue('프린터가 안 됩니다');
  });

  test('P-4 404 고객사: "이 고객사에 문의를 올릴 수 없습니다." 띠, 고객사 목록을 다시 받는다', async () => {
    on('post', '/api/portal/issues', () => errors.notFound());
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await sent('GET', '/api/portal/companies');
    const before = callsTo('GET', '/api/portal/companies').length;
    await user.click(await button('올리기'));

    await alertWith('이 고객사에 문의를 올릴 수 없습니다.');
    await eventually(() => {
      expect(callsTo('GET', '/api/portal/companies').length).toBeGreaterThan(before);
    });
  });

  test('P-4 소속 없음: 폼을 보이지 않고 "소속된 고객사가 없습니다. 지원팀에 문의하세요."', async () => {
    state.companies = [];
    renderAt('/issues/new');

    await eventually(() => {
      expect(pageText()).toContain('소속된 고객사가 없습니다. 지원팀에 문의하세요.');
    });
    expect(screen.queryAllByLabelText(/^제목/, { selector: 'input' })).toHaveLength(0);
    expect(screen.queryByRole('button', { name: '올리기' })).toBeNull();
  });

  test('P-4 내용 편집칸: 툴바(굵게 등)가 있다, 편집칸 안에서 Tab은 칸을 떠난다', async () => {
    const { user } = renderAt('/issues/new');
    const editor = await content();
    await button(/굵게/);
    await button(/링크/);

    await user.click(editor);
    await user.keyboard('첫 줄');
    await user.tab();
    expect(document.activeElement).not.toBe(editor);
    expect(editor.value).not.toContain('\t');
  });

  test('P-4 키보드: 처음 포커스는 화면 제목, 소속이 하나면 다음 Tab은 제목 칸', async () => {
    const { user } = renderAt('/issues/new');
    const titleField = await title();
    const h1 = await heading1();
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));

    await user.tab();
    expect(document.activeElement).toBe(titleField);
  });

  test('P-4 axe: 파일을 고른 폼에 접근성 위반이 없다', async () => {
    const { user } = renderAt('/issues/new');
    await title();
    await user.upload(await attachInput(), [file('error.log')]);
    await eventually(() => {
      expect(pageText()).toContain('error.log');
    });

    await expectNoAxeViolations();
  });
});
