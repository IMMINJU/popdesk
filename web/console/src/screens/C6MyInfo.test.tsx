// 1a screens.md 1절 C-6 내 정보(P-6과 같다). 세 구역: 프로필, 사진, 비밀번호(S1A-AC-16~24).
// 사진 주소는 화면이 만든다: /api/console/photos/{accountId}?v=<photoUpdatedAt>. 사진이 없으면 청하지 않는다.
import { describe, expect, test } from 'vitest';
import {
  ADMIN,
  AGENT,
  atPath,
  callsTo,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  file,
  found,
  gate,
  heading1,
  isBlocked,
  link,
  matchText,
  on,
  renderAt,
  screen,
  sent,
  text,
  within,
  type Part,
} from '../__tests__/console';
import { PHOTO_AT, PHOTO_AT_NEW, answered, photoOf, photosIn, region, rejected, route, settle, signInAs } from '../__tests__/console1a';

const profile = () => region('프로필');
const photo = () => region('사진');
const passwords = () => region('비밀번호');

const inRegion = async <T extends HTMLElement = HTMLInputElement>(box: Promise<HTMLElement>, label: string | RegExp, selector: string) => {
  const scope = within(await box);
  return found(() => scope.queryAllByLabelText<T>(label, { selector })[0], `칸 "${String(label)}"`);
};
const nameField = () => inRegion(profile(), /^이름/, 'input');
const language = () => inRegion<HTMLSelectElement>(profile(), /^언어/, 'select');
const save = async () => {
  const box = await profile();
  return found(() => within(box).queryByRole('button', { name: '저장' }), '"저장" 버튼');
};
const photoInput = (label: string) => inRegion(photo(), label, 'input[type="file"]');
const current = () => inRegion(passwords(), '지금 비밀번호', 'input');
const next = () => inRegion(passwords(), '새 비밀번호', 'input');
const again = () => inRegion(passwords(), /한 번 더/, 'input');
const changePassword = async () => {
  const box = await passwords();
  return found(() => within(box).queryByRole('button', { name: '비밀번호 바꾸기' }), '"비밀번호 바꾸기" 버튼');
};

const toasts = () => screen.queryAllByRole('status').map((el) => text(el));
const PASSWORD_DONE = '비밀번호를 바꿨습니다. 다른 기기에서는 다시 로그인해야 합니다.';

type User = ReturnType<typeof renderAt>['user'];

async function fillPasswords(user: User) {
  await user.type(await current(), 'old password 2025');
  await user.type(await next(), 'new password 2026');
  await user.type(await again(), 'new password 2026');
}

describe('C-6 내 정보', () => {
  test('S1A-AC-19 C-6 프로필 처음: 이름 칸, 읽기만 하는 이메일, 언어 고르기, "저장". 상담원에게는 배정된 고객사 목록을 읽기만으로 보인다', async () => {
    signInAs(AGENT, { companies: [{ code: 'ACME', name: '에이크미' }, { code: 'BETA', name: '베타상사' }] });
    renderAt('/me');

    expect(text(await heading1())).toBe('내 정보');
    expect(await nameField()).toHaveValue(AGENT.name);
    const lang = await language();
    expect(lang).toHaveValue('ko');
    expect(Array.from(lang.options).map((o) => o.value)).toEqual(['ko', 'en']);
    await save();
    const box = await profile();
    const emailInputs = Array.from(box.querySelectorAll('input')).filter((i) => i.value === AGENT.email);
    expect(text(box).includes(AGENT.email) || emailInputs.length > 0, '이메일').toBe(true);
    expect(emailInputs.filter((i) => !i.readOnly && !i.disabled), '고칠 수 있는 이메일 칸').toHaveLength(0);
    expect(text(box)).toContain('에이크미');
    expect(text(box)).toContain('베타상사');
    expect(within(box).queryAllByRole('link'), '고객사 목록은 읽기만').toHaveLength(0);
  });

  test('S1A-AC-16 C-6 프로필 저장 성공: 이름을 보내고 토스트를 보이며 화면 머리의 이름이 바로 바뀐다', async () => {
    const { user } = renderAt('/me');
    const name = await nameField();
    await user.clear(name);
    await user.type(name, '최운영');
    await user.click(await save());

    const [patch] = await sent('PATCH', '/api/console/me');
    expect(await patch?.body).toMatchObject({ name: '최운영' });
    await eventually(() => {
      expect(text(screen.getByRole('banner'))).toContain('최운영');
      expect(toasts().some((t) => matchText(t, /저장/)), '저장 토스트').toBe(true);
    });
    expect(text(screen.getByRole('banner'))).not.toContain(ADMIN.name);
  });

  test('S1A-AC-16 C-6 프로필 언어: 언어는 메일에만 쓰인다. en으로 저장하면 locale을 보내고 저장 토스트를 보이며, 화면 문구와 html lang은 한국어 그대로다', async () => {
    const { user } = renderAt('/me');
    expect(text(await profile())).toContain('메일에 쓰는 언어입니다. 화면은 한국어로 보입니다.');
    await user.selectOptions(await language(), 'en');
    await user.click(await save());

    const [patch] = await sent('PATCH', '/api/console/me');
    expect(await patch?.body).toMatchObject({ locale: 'en' });
    await eventually(() => {
      expect(toasts().some((t) => matchText(t, /저장/)), '저장 토스트').toBe(true);
    });
    await settle();
    expect(document.documentElement.lang).toBe('ko');
    expect(text(await heading1())).toBe('내 정보');
    expect(await language()).toHaveValue('en');
  });

  test('S1A-AC-16 C-6 프로필 언어: 언어가 en인 계정으로 열어도 화면 문구와 html lang은 한국어이고, 언어 칸에는 en이 골라져 있다', async () => {
    signInAs(ADMIN, { locale: 'en' });
    renderAt('/me');

    expect(await language()).toHaveValue('en');
    await settle();
    expect(document.documentElement.lang).toBe('ko');
    expect(text(await heading1())).toBe('내 정보');
    await save();
  });

  test('S1A-AC-60 C-6 사진 없음: 머리글자와 "사진 올리기", 받는 형식과 크기 안내. 사진을 청하지 않는다', async () => {
    renderAt('/me');

    await photoInput('사진 올리기');
    const box = await photo();
    expect(text(box)).toContain('PNG·JPEG·WebP, 2MB까지');
    expect(text(box)).toContain(Array.from(ADMIN.name)[0]);
    expect(photosIn(document.body)).toEqual([]);
    expect(within(box).queryByRole('button', { name: '지우기' })).toBeNull();
  });

  test('S1A-AC-24 C-6 사진 있음: 사진, "바꾸기", "지우기". 지우기는 묻지 않고 지우고 머리글자로 돌아간다', async () => {
    signInAs(ADMIN, { photoUpdatedAt: PHOTO_AT });
    const { user } = renderAt('/me');

    await photoInput('바꾸기');
    const box = await photo();
    expect(photosIn(box)).toEqual([photoOf(ADMIN.id, PHOTO_AT)]);
    await user.click(await found(() => within(box).queryByRole('button', { name: '지우기' }), '"지우기" 버튼'));

    await sent('DELETE', '/api/console/me/photo');
    expect(screen.queryByRole('alertdialog'), '지우기 확인 대화상자').toBeNull();
    await eventually(() => {
      expect(photosIn(document.body)).toEqual([]);
    });
    await photoInput('사진 올리기');
  });

  test('S1A-AC-20 C-6 사진 올리는 중과 성공: 고르면 바로 part `file`로 올리고 그동안 칸을 막으며, 끝나면 새 시각의 사진을 그린다', async () => {
    const release = gate();
    signInAs(ADMIN, { photoUpdatedAt: PHOTO_AT });
    route('post', '/api/console/me/photo', async () => {
      await release.opened;
      return Response.json({ ...ADMIN, photoUpdatedAt: PHOTO_AT_NEW }, { status: 201 });
    });
    const { user } = renderAt('/me');
    await user.upload(await photoInput('바꾸기'), file('me.png', 1024, 'image/png'));

    const [upload] = await sent('POST', '/api/console/me/photo');
    const parts = (await upload?.body) as Part[];
    expect(parts.map((p) => p.name)).toEqual(['file']);
    const box = await photo();
    await eventually(() => {
      expect(within(box).getByLabelText('바꾸기', { selector: 'input[type="file"]' }), '올리는 중의 파일 칸').toBeDisabled();
      expect(isBlocked(within(box).getByRole('button', { name: /지우기/ })), '올리는 중의 지우기').toBe(true);
    });

    release.open();
    await eventually(() => {
      expect(photosIn(box)).toEqual([photoOf(ADMIN.id, PHOTO_AT_NEW)]);
    });
  });

  test('S1A-AC-22 S1A-AC-23 C-6 사진 오류: 413, UNSUPPORTED_IMAGE, IMAGE_TOO_LARGE마다 사진 구역의 오류 띠에 서로 다른 까닭을 보이고 앞 사진은 그대로 둔다', async () => {
    const answers = {
      413: () => errors.payloadTooLarge(),
      UNSUPPORTED_IMAGE: () => rejected('UNSUPPORTED_IMAGE'),
      IMAGE_TOO_LARGE: () => rejected('IMAGE_TOO_LARGE'),
    };
    const reasons: string[] = [];
    for (const [name, answer] of Object.entries(answers)) {
      signInAs(ADMIN, { photoUpdatedAt: PHOTO_AT });
      route('post', '/api/console/me/photo', answer);
      const { user, unmount } = renderAt('/me');
      await user.upload(await photoInput('바꾸기'), file('me.png', 1024, 'image/png'));

      const box = await photo();
      const band = await found(() => within(box).queryByRole('alert'), `${name}: 사진 구역의 오류 띠`);
      expect(text(band), name).not.toBe('');
      expect(text(band), `${name}: 서버 문구를 그대로 보이지 않는다`).not.toContain('(server text)');
      expect(photosIn(box), `${name}: 앞 사진`).toEqual([photoOf(ADMIN.id, PHOTO_AT)]);
      reasons.push(text(band));
      unmount();
    }

    expect(new Set(reasons).size, '까닭별 문구').toBe(3);
  });

  test('S1A-AC-17 C-6 비밀번호 성공: 지금·새 비밀번호를 보내고 세 칸을 비우며 토스트를 보인다. 이 화면의 세션은 이어진다', async () => {
    const { user } = renderAt('/me');
    await fillPasswords(user);
    await user.click(await changePassword());

    const [put] = await sent('PUT', '/api/console/me/password');
    expect(await put?.body).toEqual({ currentPassword: 'old password 2025', newPassword: 'new password 2026' });
    await eventually(() => {
      expect(toasts().some((t) => t.includes(PASSWORD_DONE)), '토스트').toBe(true);
    });
    expect(await current()).toHaveValue('');
    expect(await next()).toHaveValue('');
    expect(await again()).toHaveValue('');
    expect(window.location.pathname).toBe('/me');
  });

  test('S1A-AC-18 C-6 비밀번호 400 CURRENT_PASSWORD_WRONG: 지금 비밀번호 칸 아래 오류. 다른 칸은 지우지 않는다', async () => {
    on('put', '/api/console/me/password', () => rejected('CURRENT_PASSWORD_WRONG'));
    const { user } = renderAt('/me');
    await fillPasswords(user);
    await user.click(await changePassword());

    await expectFieldError(await current());
    expect(await next()).toHaveValue('new password 2026');
    expect(await again()).toHaveValue('new password 2026');
  });

  test('S1A-AC-18 C-6 비밀번호 429: 다섯 번 틀린 뒤의 막힘도 "요청이 많습니다. N초 뒤 다시 하세요." 띠로 보이고 버튼을 막는다', async () => {
    on('put', '/api/console/me/password', () => errors.rateLimited(900));
    const { user } = renderAt('/me');
    await fillPasswords(user);
    await user.click(await changePassword());

    const box = await passwords();
    await found(
      () => within(box).queryAllByRole('alert').find((el) => matchText(text(el), /요청이 많습니다\. (900|899)초 뒤 다시 하세요\./)),
      '비밀번호 구역의 429 띠',
    );
    expect(isBlocked(await changePassword())).toBe(true);
  });

  test('S1A-AC-58 C-6 떠난 뒤 온 답: 비밀번호를 바꾸는 중에 다른 화면으로 가면, 뒤에 온 성공의 토스트를 그 화면에 보이지 않는다', async () => {
    const release = gate();
    on('put', '/api/console/me/password', async () => {
      await release.opened;
      return new Response(null, { status: 204 });
    });
    const { user } = renderAt('/me');
    await fillPasswords(user);
    await user.click(await changePassword());
    await sent('PUT', '/api/console/me/password');
    await user.click(await link('문의'));
    // 쓰던 값이 있어 "떠나기"를 물으면(첫 묶음 2절) 떠난다.
    await eventually(() => {
      expect(window.location.pathname === '/issues' || screen.queryByRole('button', { name: '떠나기' }) !== null, '떠났거나 떠나기를 물음').toBe(true);
    });
    const leave = screen.queryByRole('button', { name: '떠나기' });
    if (leave) await user.click(leave);
    await atPath('/issues');

    const done = answered('PUT', '/api/console/me/password');
    release.open();
    await done;
    await settle();

    expect(toasts().filter((t) => t.includes('비밀번호를 바꿨습니다'))).toEqual([]);
    expect(window.location.pathname).toBe('/issues');
    expect(callsTo('PUT', '/api/console/me/password')).toHaveLength(1);
  });

  test('C-6 axe: 사진이 있는 내 정보 화면에 접근성 위반이 없다', async () => {
    signInAs(ADMIN, { photoUpdatedAt: PHOTO_AT });
    renderAt('/me');
    await photoInput('바꾸기');
    await current();

    await expectNoAxeViolations();
  });
});
