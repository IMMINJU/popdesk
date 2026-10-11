// 1a screens.md 1절 P-6·C-6 내 정보의 사진 구역: 사진 없음, 사진 있음, 올리는 중, 오류(S1A-AC-20, 24, 60).
// 자르기 화면은 없고, 고른 파일은 화면이 올린다(onUpload).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, file, found, isBlocked, text } from '../__tests__/support';
import { PhotoField, type PhotoFieldProps } from './PhotoField';

const PHOTO = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';
const HELP = 'PNG·JPEG·WebP, 2MB까지';

function photoField(over: Partial<PhotoFieldProps> = {}) {
  return (
    <PhotoField
      name="김하나"
      helpText={HELP}
      accept="image/png,image/jpeg,image/webp"
      onUpload={() => undefined}
      onRemove={() => undefined}
      uploadLabel="사진 올리기"
      changeLabel="바꾸기"
      removeLabel="지우기"
      {...over}
    />
  );
}

const input = (label: string) =>
  found(() => screen.queryAllByLabelText<HTMLInputElement>(label, { selector: 'input[type="file"]' })[0], `파일 칸 "${label}"`);

const photos = (container: HTMLElement) => Array.from(container.querySelectorAll('img')).map((img) => img.getAttribute('src'));

describe('PhotoField', () => {
  test('S1A-AC-60 PhotoField 사진 없음: 머리글자와 "사진 올리기" 파일 칸, 형식·크기 안내. 지우기는 없고, 고른 파일은 onUpload로 나간다', async () => {
    const onUpload = vi.fn();
    const { container } = render(photoField({ onUpload }));
    const user = userEvent.setup();

    const pick = await input('사진 올리기');
    expect(pick).toHaveAttribute('accept', 'image/png,image/jpeg,image/webp');
    expect(pick).not.toHaveAttribute('multiple');
    expect(photos(container)).toEqual([]);
    expect(text(container)).toContain('김');
    expect(text(container)).toContain(HELP);
    expect(screen.queryByRole('button', { name: '지우기' })).toBeNull();

    const picked = file('me.png', 1024, 'image/png');
    await user.upload(pick, picked);

    expect(onUpload).toHaveBeenCalledTimes(1);
    expect(onUpload).toHaveBeenCalledWith(picked);
  });

  test('S1A-AC-24 PhotoField 사진 있음: 사진, "바꾸기" 파일 칸, "지우기"는 묻지 않고 onRemove', async () => {
    const onRemove = vi.fn();
    const onUpload = vi.fn();
    const { container } = render(photoField({ photoUrl: PHOTO, onRemove, onUpload }));
    const user = userEvent.setup();

    expect(photos(container)).toEqual([PHOTO]);
    await input('바꾸기');
    await user.click(await found(() => screen.queryByRole('button', { name: '지우기' }), '"지우기" 버튼'));

    expect(onRemove).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '확인 대화상자').toBeNull();
    expect(onUpload).not.toHaveBeenCalled();
  });

  test('S1A-AC-20 PhotoField 올리는 중: 파일 칸과 지우기를 막고, 그동안 누른 지우기는 나가지 않는다', async () => {
    const onRemove = vi.fn();
    render(photoField({ photoUrl: PHOTO, uploading: true, onRemove }));
    const user = userEvent.setup();

    const pick = await input('바꾸기');
    const remove = await found(() => screen.queryByRole('button', { name: /지우기/ }), '"지우기" 버튼');
    expect(pick, '올리는 중의 파일 칸').toBeDisabled();
    expect(isBlocked(remove), '올리는 중의 지우기 버튼').toBe(true);

    await user.click(remove);
    expect(onRemove).not.toHaveBeenCalled();
  });

  test('S1A-AC-22 PhotoField 오류: 까닭은 role="alert"로 읽히고 앞 사진은 그대로다', async () => {
    const reason = '2MB를 넘습니다. 더 작은 사진을 올리세요.';
    const { container } = render(photoField({ photoUrl: PHOTO, error: reason }));

    const alert = await found(() => screen.queryByRole('alert'), '오류 role="alert"');
    expect(text(alert)).toContain(reason);
    expect(photos(container)).toEqual([PHOTO]);
    await input('바꾸기');
  });

  test('PhotoField axe: 사진 없음·사진 있음·올리는 중·오류에 접근성 위반이 없다', async () => {
    render(
      <div>
        {photoField()}
        {photoField({ photoUrl: PHOTO })}
        {photoField({ photoUrl: PHOTO, uploading: true })}
        {photoField({ photoUrl: PHOTO, error: '지원하지 않는 형식입니다.' })}
      </div>,
    );
    await found(() => screen.queryByRole('alert'), '오류 role="alert"');

    await expectNoAxeViolations();
  });
});
