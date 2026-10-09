// screens.md 5절 9 Alert (오류 띠). 오류, 경고, 안내, 성공. 닫기 있음·없음. role="alert" 또는 "status".
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, found, spokenText, text } from '../__tests__/support';
import { Alert } from './Alert';

describe('Alert', () => {
  test('Alert 역할: 오류·경고는 role="alert", 안내·성공은 role="status"', () => {
    render(
      <div>
        <Alert tone="error">이 작업을 할 권한이 없습니다.</Alert>
        <Alert tone="warning">첨부 2개를 올리지 못했습니다.</Alert>
        <Alert tone="info">로그인이 끝났습니다. 다시 로그인하세요.</Alert>
        <Alert tone="success">저장했습니다.</Alert>
      </div>,
    );

    expect(screen.getAllByRole('alert').map((el) => text(el))).toEqual([
      '이 작업을 할 권한이 없습니다.',
      '첨부 2개를 올리지 못했습니다.',
    ]);
    expect(screen.getAllByRole('status').map((el) => text(el))).toEqual(['로그인이 끝났습니다. 다시 로그인하세요.', '저장했습니다.']);
  });

  test('Alert live: live를 주면 tone과 상관없이 그 역할이다', () => {
    render(
      <Alert tone="info" live="alert">
        세션 안내
      </Alert>,
    );

    expect(text(screen.getByRole('alert'))).toBe('세션 안내');
  });

  test('Alert 닫기 있음: closeLabel이 버튼 이름이고 누르면 onClose, 닫기 글자 "×"는 읽지 않는다', async () => {
    const onClose = vi.fn();
    render(
      <Alert tone="error" onClose={onClose} closeLabel="닫기">
        서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.
      </Alert>,
    );
    const user = userEvent.setup();

    const band = screen.getByRole('alert');
    const close = await found(() => within(band).queryByRole('button', { name: '닫기' }), '닫기 버튼');
    await user.click(close);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(spokenText(band)).not.toContain('×');
  });

  test('Alert 닫기 없음: onClose가 없으면 버튼이 없다', () => {
    render(<Alert tone="warning">첨부 2개를 올리지 못했습니다.</Alert>);

    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  test('Alert 제목과 동작: 제목·본문·동작(다시 불러오기)이 한 띠 안에 있다', () => {
    render(
      <Alert tone="error" title="불러오지 못했습니다" action={<button type="button">다시 불러오기</button>}>
        서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.
      </Alert>,
    );

    const band = screen.getByRole('alert');
    expect(text(band)).toContain('불러오지 못했습니다');
    expect(text(band)).toContain('서버에 연결하지 못했습니다.');
    expect(within(band).getByRole('button', { name: '다시 불러오기' })).toBeInTheDocument();
  });

  test('Alert axe: 네 종류와 닫기 버튼에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Alert tone="error" onClose={() => undefined} closeLabel="닫기">
          오류
        </Alert>
        <Alert tone="warning">경고</Alert>
        <Alert tone="info">안내</Alert>
        <Alert tone="success" title="성공">
          저장했습니다.
        </Alert>
      </div>,
    );

    await expectNoAxeViolations();
  });
});
