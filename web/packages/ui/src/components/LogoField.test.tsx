// 9번 묶음 screens.md 2.2 C-14의 로고 칸 LogoField: 없음, 다른 테마 것을 씀, 있음, 올리는 중, 실패(S9-AC-30). 고른 파일은
// 화면이 바로 올린다(onUpload). "올리기"·"바꾸기"는 숨긴 파일 입력의 label이고, 무리 이름은 칸 이름이다.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, file, found, text } from '../__tests__/support';
import { LogoField, type LogoFieldProps } from './LogoField';

const LOGO = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';
const OTHER = 'data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACw=';
const HELP = 'PNG, JPEG, WebP, 512KB까지. 가로 512px, 세로 128px 안으로 줄여 저장합니다. 하나만 올리면 두 테마에 같이 씁니다.';

function logoField(over: Partial<LogoFieldProps> = {}) {
  return (
    <LogoField
      theme="light"
      label="밝은 테마 로고"
      logo={null}
      helpText={HELP}
      accept="image/png,image/jpeg,image/webp"
      onUpload={() => undefined}
      onRemove={() => undefined}
      uploadLabel="올리기"
      changeLabel="바꾸기"
      removeLabel="지우기"
      {...over}
    />
  );
}

const group = (name = '밝은 테마 로고') => found(() => screen.queryByRole('group', { name }), `무리 "${name}"`);

const input = (root: HTMLElement, label: string) =>
  found(() => within(root).queryAllByLabelText<HTMLInputElement>(label, { selector: 'input[type="file"]' })[0], `파일 칸 "${label}"`);

const images = (root: HTMLElement) => Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('src'));

describe('LogoField (9번 묶음)', () => {
  test('S9-AC-30 LogoField 없음(다른 테마도 없음): "로고 없음", "올리기" 파일 칸과 안내, 지우기 없음. 고른 파일은 onUpload로 나간다', async () => {
    const onUpload = vi.fn();
    render(logoField({ onUpload }));
    const user = userEvent.setup();

    const box = await group();
    const pick = await input(box, '올리기');
    expect(pick).toHaveAttribute('accept', 'image/png,image/jpeg,image/webp');
    expect(text(box)).toContain('로고 없음');
    expect(text(box)).toContain(HELP);
    expect(images(box)).toEqual([]);
    expect(within(box).queryByRole('button', { name: '지우기' })).toBeNull();

    const picked = file('logo.png', 2048, 'image/png');
    await user.upload(pick, picked);

    expect(onUpload).toHaveBeenCalledTimes(1);
    expect(onUpload).toHaveBeenCalledWith(picked);
  });

  test.each([
    { theme: 'dark' as const, label: '어두운 테마 로고', from: 'light' as const, words: '없음. 밝은 테마 로고를 같이 씁니다' },
    { theme: 'light' as const, label: '밝은 테마 로고', from: 'dark' as const, words: '없음. 어두운 테마 로고를 같이 씁니다' },
  ])('S9-AC-30 LogoField 없음, 다른 테마에 있음($theme): 다른 테마 로고를 흐리게(투명도 0.5)와 "$words", "올리기"', async ({ theme, label, from, words }) => {
    render(logoField({ theme, label, fallback: { url: OTHER, from } }));

    const box = await group(label);
    expect(text(box)).toContain(words);
    // 투명도는 CSS라 jsdom이 계산하지 않는다(story가 본다): 다른 테마 로고가 그려졌는지만 본다.
    expect(images(box)).toEqual([OTHER]);
    expect(box.querySelector('img')).toHaveAttribute('alt', '');
    await input(box, '올리기');
    expect(within(box).queryByRole('button', { name: '지우기' })).toBeNull();
  });

  test('S9-AC-30 LogoField 있음: 그 테마 바탕 위의 로고(alt=""), "가로 × 세로 px", "바꾸기" 파일 칸, "지우기"는 묻지 않고 onRemove', async () => {
    const onRemove = vi.fn();
    const onUpload = vi.fn();
    render(logoField({ theme: 'dark', label: '어두운 테마 로고', logo: { url: LOGO, width: 512, height: 64 }, onRemove, onUpload }));
    const user = userEvent.setup();

    const box = await group('어두운 테마 로고');
    expect(images(box)).toEqual([LOGO]);
    expect(box.querySelector('img')).toHaveAttribute('alt', '');
    expect(text(box)).toMatch(/512 ?× ?64 ?px/);
    expect(text(box)).not.toContain('로고 없음');
    await input(box, '바꾸기');
    await user.click(await found(() => within(box).queryByRole('button', { name: '지우기' }), '"지우기"'));

    expect(onRemove).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '확인 대화상자').toBeNull();
    expect(onUpload).not.toHaveBeenCalled();
  });

  test('S9-AC-30 LogoField 올리는 중: "올리는 중…", 칸에 aria-busy, 파일 입력은 disabled가 아니라 aria-disabled이고 고른 파일은 나가지 않는다', async () => {
    const onUpload = vi.fn();
    const onRemove = vi.fn();
    render(logoField({ logo: { url: LOGO, width: 128, height: 128 }, uploading: true, onUpload, onRemove }));
    const user = userEvent.setup();

    const box = await group();
    expect(text(box)).toContain('올리는 중…');
    expect(box.closest('[aria-busy="true"]') ?? box.querySelector('[aria-busy="true"]'), 'aria-busy').not.toBeNull();
    const pick = await input(box, '바꾸기');
    expect(pick).toHaveAttribute('aria-disabled', 'true');
    expect(pick.disabled, 'disabled가 아니다(초점이 body로 가지 않게)').toBe(false);

    await user.upload(pick, file('again.png', 1024, 'image/png'));
    const remove = within(box).queryByRole('button', { name: /지우기/ });
    if (remove) await user.click(remove);

    expect(onUpload).not.toHaveBeenCalled();
    expect(onRemove).not.toHaveBeenCalled();
  });

  test('S9-AC-30 LogoField 실패: 칸 아래 오류 글을 파일 입력의 설명으로 묶고 있던 로고는 그대로다', async () => {
    const reason = '로고가 512KB를 넘습니다. 더 작은 파일을 올리세요.';
    render(logoField({ logo: { url: LOGO, width: 512, height: 64 }, error: reason }));

    const box = await group();
    const pick = await input(box, '바꾸기');
    const described = (pick.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .map((id) => text(document.getElementById(id)))
      .join(' ');
    expect(described).toContain(reason);
    expect(images(box)).toEqual([LOGO]);
    expect(text(box)).toMatch(/512 ?× ?64 ?px/);
  });

  test('S9-AC-30 LogoField axe: 다섯 상태에 접근성 위반이 없다', async () => {
    render(
      <div>
        {logoField()}
        {logoField({ theme: 'dark', label: '어두운 테마 로고', fallback: { url: OTHER, from: 'light' } })}
        {logoField({ label: '로고 셋', logo: { url: LOGO, width: 512, height: 64 } })}
        {logoField({ label: '로고 넷', logo: { url: LOGO, width: 512, height: 64 }, uploading: true })}
        {logoField({ label: '로고 다섯', logo: { url: LOGO, width: 512, height: 64 }, error: 'PNG, JPEG, WebP 이미지만 올릴 수 있습니다.' })}
      </div>,
    );
    await group('로고 다섯');
    expect(document.querySelectorAll('img').length, '로고가 그려짐').toBeGreaterThanOrEqual(4);

    await expectNoAxeViolations();
  });
});
