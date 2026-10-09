// screens.md 5절 28 CopyField. C-10 초대 링크. 기본, 복사됨, 클립보드 못 씀(링크 칸을 전체 선택해 둔다).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations } from '../__tests__/support';
import { CopyField } from './CopyField';

const LINK = 'https://portal.example.test/invite#token=inv-7Qx2pLk9VtA3';

const field = () => screen.getByRole('textbox', { name: '초대 링크' });

describe('CopyField', () => {
  test('CopyField 기본: 이름표로 찾는 읽기 전용 칸에 링크, "복사" 버튼은 onCopy', async () => {
    const onCopy = vi.fn();
    render(<CopyField label="초대 링크" value={LINK} onCopy={onCopy} copyLabel="복사" helpText="이 링크는 지금만 보입니다" />);
    const user = userEvent.setup();

    expect(field()).toHaveValue(LINK);
    expect(field()).toHaveAttribute('readonly');
    await user.click(screen.getByRole('button', { name: '복사' }));
    expect(onCopy).toHaveBeenCalledTimes(1);
  });

  test('CopyField 도움말: "이 링크는 지금만 보입니다"를 칸의 aria-describedby로 잇는다', () => {
    render(<CopyField label="초대 링크" value={LINK} onCopy={() => undefined} copyLabel="복사" helpText="이 링크는 지금만 보입니다" />);

    expect(describedText(field())).toContain('이 링크는 지금만 보입니다');
  });

  test('CopyField 클립보드 못 씀: 링크 칸을 전체 선택해 두고 포커스를 칸으로, "Ctrl+C로 복사하세요"를 읽는다', () => {
    const { rerender } = render(
      <CopyField label="초대 링크" value={LINK} onCopy={() => undefined} copyLabel="복사" helpText="이 링크는 지금만 보입니다" />,
    );

    rerender(
      <CopyField
        label="초대 링크"
        value={LINK}
        onCopy={() => undefined}
        copyLabel="복사"
        status="unavailable"
        helpText="Ctrl+C로 복사하세요"
      />,
    );

    const input = field() as HTMLInputElement;
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(LINK.length);
    expect(describedText(input)).toContain('Ctrl+C로 복사하세요');
  });

  test('CopyField axe: 기본·복사됨·클립보드 못 씀에 접근성 위반이 없다', async () => {
    render(
      <div>
        <CopyField label="초대 링크" value={LINK} onCopy={() => undefined} copyLabel="복사" helpText="이 링크는 지금만 보입니다" />
        <CopyField label="다른 링크" value={LINK} onCopy={() => undefined} copyLabel="복사" status="copied" />
        <CopyField label="세 번째 링크" value={LINK} onCopy={() => undefined} copyLabel="복사" status="unavailable" helpText="Ctrl+C로 복사하세요" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
