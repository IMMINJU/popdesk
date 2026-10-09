// screens.md 5절 22 AttachmentList. 데이터, 비어 있음, 지워진 흔적, 내부 표시, 지우는 중(P-5, C-4).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, found, spokenText, text } from '../__tests__/support';
import { AttachmentList, type AttachmentItem, type AttachmentListProps } from './AttachmentList';

const T0 = '2026-10-08T03:15:00Z';
const BASE = '/api/console/issues/ACME-7/attachments';

function item(over: Partial<AttachmentItem> = {}): AttachmentItem {
  return {
    id: 'a1',
    name: 'log.txt',
    size: 2048,
    href: `${BASE}/a1`,
    uploaderLabel: '나',
    createdAt: T0,
    ...over,
  };
}

function list(over: Partial<AttachmentListProps> = {}) {
  return (
    <AttachmentList
      title="첨부"
      items={[item()]}
      emptyText="첨부 없음"
      deleteLabel="지우기"
      deletedLabel="지워진 첨부"
      internalLabel="내부"
      {...over}
    />
  );
}

/** A deleted attachment: no link, no name (ATT-5, COM-7). */
const deletedItem = (id: string): AttachmentItem => ({ id, name: '', size: 0, uploaderLabel: '나', createdAt: T0, deleted: true });

const row = (name: RegExp) =>
  found(() => screen.queryAllByRole('listitem').find((li) => name.test(text(li))), `첨부 줄 ${String(name)}`);

describe('AttachmentList', () => {
  test('AttachmentList 데이터: 제목, 줄마다 내려받기 링크(파일 이름), 올린 사람, 올린 시각 <time>', async () => {
    render(list({ items: [item(), item({ id: 'a2', name: 'guide.pdf', href: `${BASE}/a2`, uploaderLabel: '김상담' })] }));

    expect(screen.getByRole('heading', { name: '첨부' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /log\.txt/ })).toHaveAttribute('href', `${BASE}/a1`);
    const guide = await row(/guide\.pdf/);
    expect(text(guide)).toContain('김상담');
    expect(guide.querySelector('time')?.getAttribute('datetime')).toBe(T0);
  });

  test('AttachmentList 비어 있음: 제목은 두고 "첨부 없음"', () => {
    render(list({ items: [] }));

    expect(screen.getByRole('heading', { name: '첨부' })).toBeInTheDocument();
    expect(text(document.body)).toContain('첨부 없음');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  test('AttachmentList 지우기: canDelete인 줄에만 지우기 버튼, 누르면 onDelete(id)', async () => {
    const onDelete = vi.fn();
    render(list({ items: [item({ canDelete: true }), item({ id: 'a2', name: 'guide.pdf', href: `${BASE}/a2` })], onDelete }));
    const user = userEvent.setup();

    const buttons = screen.queryAllByRole('button', { name: /지우기/ });
    expect(buttons).toHaveLength(1);
    const mine = await row(/log\.txt/);
    const del = await found(() => within(mine).queryByRole('button', { name: /지우기/ }), 'log.txt 줄의 지우기 버튼');
    await user.click(del);
    expect(onDelete).toHaveBeenCalledWith('a1');
  });

  test('AttachmentList 지우는 중: 그 줄의 지우기 버튼은 aria-disabled이고 다시 눌러도 부르지 않는다', async () => {
    const onDelete = vi.fn();
    render(list({ items: [item({ canDelete: true, deleting: true })], onDelete }));
    const user = userEvent.setup();

    const del = await found(() => screen.queryByRole('button', { name: /지우기/ }), '지우기 버튼');
    expect(del).toHaveAttribute('aria-disabled', 'true');
    await user.click(del);
    expect(onDelete).not.toHaveBeenCalled();
  });

  test('AttachmentList 지워진 흔적: "지워진 첨부"로 남고 링크·지우기 버튼이 없다', async () => {
    render(list({ items: [{ ...deletedItem('gone'), canDelete: true }] }));

    const gone = await row(/지워진 첨부/);
    expect(within(gone).queryAllByRole('link')).toHaveLength(0);
    expect(within(gone).queryAllByRole('button')).toHaveLength(0);
  });

  test('AttachmentList 내부 표시: 내부 댓글의 첨부에 자물쇠와 "내부" 글자, 아이콘은 읽지 않는다', async () => {
    render(list({ items: [item({ internal: true })] }));

    const r = await row(/log\.txt/);
    expect(spokenText(r)).toContain('내부');
    expect(r.querySelectorAll('[aria-hidden="true"]').length, '자물쇠 아이콘(aria-hidden)').toBeGreaterThan(0);
  });

  test('AttachmentList axe: 데이터·내부·지워진 흔적·지우기 버튼에 접근성 위반이 없다', async () => {
    render(
      list({
        items: [
          item({ canDelete: true }),
          item({ id: 'a2', name: 'guide.pdf', href: `${BASE}/a2`, internal: true }),
          deletedItem('a3'),
        ],
      }),
    );
    await row(/guide\.pdf/);

    await expectNoAxeViolations();
  });
});
