// 3 묶음 screens.md C-4의 접힌 부분(이력, "전 내용 보기", 고치기의 "지금 내용 보기"). 화면 테스트는 단추를 눌러 내용을 본다.
// 여기서는 조각의 약속을 본다: 접힌 내용은 그리지 않고, aria-expanded와 aria-controls가 열림을 따르고, onToggle은 새 상태를
// 받고, section이면 단추가 2단계 제목 안에 있다.
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { Disclosure } from './Disclosure';

describe('Disclosure', () => {
  test('C-4 접힌 부분: 처음에는 닫혀 내용을 그리지 않고, aria-controls가 없다', () => {
    render(<Disclosure label="이력">내용</Disclosure>);

    const button = screen.getByRole('button', { name: '이력' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).not.toHaveAttribute('aria-controls');
    expect(screen.queryByText('내용')).toBeNull();
  });

  test('C-4 접힌 부분: 누르면 열려 내용을 그리고 aria-controls가 그 내용을 가리키며, 다시 누르면 닫는다. onToggle은 새 상태를 받는다', () => {
    const onToggle = vi.fn<(open: boolean) => void>();
    render(
      <Disclosure label="전 내용 보기" onToggle={onToggle}>
        <p>예전 글</p>
      </Disclosure>,
    );
    const button = screen.getByRole('button', { name: '전 내용 보기' });

    fireEvent.click(button);

    expect(button).toHaveAttribute('aria-expanded', 'true');
    const controlled = button.getAttribute('aria-controls');
    expect(controlled).toBeTruthy();
    expect(document.getElementById(controlled ?? '')).toContainElement(screen.getByText('예전 글'));

    fireEvent.click(button);

    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('예전 글')).toBeNull();
    expect(onToggle.mock.calls).toEqual([[true], [false]]);
  });

  test('C-4 접힌 부분: section은 단추가 2단계 제목 안에 있고, 그 밖은 제목이 없다', () => {
    const { unmount } = render(
      <Disclosure label="이력" kind="section">
        내용
      </Disclosure>,
    );
    expect(screen.getByRole('heading', { level: 2, name: '이력' })).toContainElement(screen.getByRole('button', { name: '이력' }));
    unmount();

    render(
      <Disclosure label="지금 내용 보기" kind="inline">
        내용
      </Disclosure>,
    );
    expect(screen.queryByRole('heading')).toBeNull();
    expect(screen.getByRole('button', { name: '지금 내용 보기' })).toBeInTheDocument();
  });

  test('C-4 접힌 부분: 둘을 그려도 aria-controls는 서로 다른 내용을 가리킨다', () => {
    render(
      <>
        <Disclosure label="하나">첫째</Disclosure>
        <Disclosure label="둘">둘째</Disclosure>
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: '하나' }));
    fireEvent.click(screen.getByRole('button', { name: '둘' }));

    const first = screen.getByRole('button', { name: '하나' }).getAttribute('aria-controls');
    const second = screen.getByRole('button', { name: '둘' }).getAttribute('aria-controls');
    expect(first).not.toBe(second);
    expect(document.getElementById(second ?? '')).toHaveTextContent('둘째');
  });
});
