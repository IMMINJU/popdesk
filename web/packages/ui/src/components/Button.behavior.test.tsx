// screens.md 5절 1 Button, 2 IconButton. C-10 변이 테스트가 찾은 빈칸: blocked, 앞 아이콘, IconButton 툴팁.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { spokenText, text } from '../__tests__/support';
import { Button } from './Button';
import { IconButton } from './IconButton';

describe('Button 동작', () => {
  test('Button blocked(429 기다림): aria-disabled="true", 글자는 그대로, 눌러도 onClick을 부르지 않는다', async () => {
    const onClick = vi.fn();
    render(
      <Button blocked pendingLabel="보내는 중…" onClick={onClick}>
        다시 보내기
      </Button>,
    );
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '다시 보내기' });
    expect(b).toHaveAttribute('aria-disabled', 'true');
    expect(b).not.toBeDisabled();
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
  });

  test('Button blocked: submit 버튼이면 폼을 보내지 않는다', async () => {
    const onSubmit = vi.fn((e: { preventDefault: () => void }) => {
      e.preventDefault();
    });
    render(
      <form onSubmit={onSubmit}>
        <Button type="submit" blocked>
          보내기
        </Button>
      </form>,
    );
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '보내기' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test('Button 앞 아이콘: 아이콘은 읽지 않고(aria-hidden) 이름은 글자뿐, 보내는 중이 아니면 아이콘을 그린다', () => {
    render(<Button icon="★">복사</Button>);

    const b = screen.getByRole('button', { name: '복사' });
    expect(text(b)).toBe('★복사');
    expect(spokenText(b)).toBe('복사');
  });

  test('Button 아이콘 없음: 보내는 중이 아니면 읽지 않는 표시가 없다', () => {
    render(<Button>복사</Button>);

    const b = screen.getByRole('button', { name: '복사' });
    expect(b.querySelectorAll('[aria-hidden="true"]')).toHaveLength(0);
  });

  test('Button 보내는 중: 앞 아이콘 대신 회전 표시를 그린다', () => {
    render(
      <Button icon="★" pending pendingLabel="복사하는 중…">
        복사
      </Button>,
    );

    const b = screen.getByRole('button', { name: /복사하는 중…/ });
    expect(text(b)).not.toContain('★');
  });
});

describe('IconButton 동작', () => {
  test('IconButton 툴팁: 기본은 이름을 title로 보이고, tooltip={false}면 title이 없다', () => {
    render(
      <div>
        <IconButton label="옆 메뉴" icon="≡" />
        <IconButton label="닫기" icon="×" tooltip={false} />
      </div>,
    );

    expect(screen.getByRole('button', { name: '옆 메뉴' })).toHaveAttribute('title', '옆 메뉴');
    expect(screen.getByRole('button', { name: '닫기' })).not.toHaveAttribute('title');
  });

  test('IconButton 보내는 중: aria-disabled이고 눌러도 onClick을 부르지 않는다', async () => {
    const onClick = vi.fn();
    render(<IconButton label="새로 고침" icon="↻" pending onClick={onClick} />);
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '새로 고침' });
    expect(b).toHaveAttribute('aria-disabled', 'true');
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
  });

  test('IconButton 펼침: expanded와 controls가 aria-expanded, aria-controls로 나간다', () => {
    render(<IconButton label="옆 메뉴" icon="≡" expanded={false} controls="menu" />);

    const b = screen.getByRole('button', { name: '옆 메뉴' });
    expect(b).toHaveAttribute('aria-expanded', 'false');
    expect(b).toHaveAttribute('aria-controls', 'menu');
  });
});
