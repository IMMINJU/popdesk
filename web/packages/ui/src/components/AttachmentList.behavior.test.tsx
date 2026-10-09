// screens.md 5절 22 AttachmentList (P-5 내려받기, C-4). C-10 변이 테스트가 찾은 빈칸:
// 제목 수준, 영역 이름, 내려받기 콜백, 줄 안 시각의 Tab 자리, 지우기 버튼 설명.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { describedText, found, tabbables, text } from '../__tests__/support';
import { AttachmentList, type AttachmentItem, type AttachmentListProps } from './AttachmentList';

const T0 = '2026-10-08T03:15:00Z';

function item(over: Partial<AttachmentItem> = {}): AttachmentItem {
  return { id: 'a1', name: 'log.txt', size: 2048, href: '/api/portal/issues/ACME-7/attachments/a1', uploaderLabel: '나', createdAt: T0, ...over };
}

function list(over: Partial<AttachmentListProps> = {}) {
  return <AttachmentList title="첨부" items={[item()]} emptyText="첨부 없음" deleteLabel="지우기" {...over} />;
}

describe('AttachmentList 동작', () => {
  test('AttachmentList 제목 수준: 기본은 h2, headingLevel 3이면 h3', () => {
    const { rerender } = render(list());
    expect(screen.getByRole('heading', { name: '첨부' }).tagName).toBe('H2');

    rerender(list({ headingLevel: 3 }));
    expect(screen.getByRole('heading', { name: '첨부' }).tagName).toBe('H3');
  });

  test('AttachmentList 영역 이름: 제목이 영역(region)의 이름이다', () => {
    render(list());

    expect(screen.getByRole('region', { name: '첨부' })).toBeInTheDocument();
  });

  test('AttachmentList 내려받기: 링크를 누르면 onDownload(그 첨부)를 부른다(화면이 HEAD로 먼저 물어볼 수 있게)', async () => {
    const onDownload = vi.fn((_: AttachmentItem, e: { preventDefault: () => void }) => {
      e.preventDefault();
    });
    render(list({ items: [item(), item({ id: 'a2', name: 'shot.png', href: '/x/a2' })], onDownload }));
    const user = userEvent.setup();

    await user.click(screen.getByRole('link', { name: 'shot.png' }));
    expect(onDownload).toHaveBeenCalledTimes(1);
    expect(onDownload.mock.lastCall?.[0]).toMatchObject({ id: 'a2', name: 'shot.png' });
  });

  test('AttachmentList 한 줄의 Tab 자리: 링크와 지우기 버튼뿐, 올린 시각에는 Tab이 닿지 않는다', async () => {
    render(list({ items: [item({ canDelete: true })], onDelete: () => undefined }));

    const li = await found(() => screen.queryAllByRole('listitem')[0], '첨부 줄');
    expect(li.querySelector('time')).not.toBeNull();
    expect(tabbables(li).map((el) => el.tagName)).toEqual(['A', 'BUTTON']);
  });

  test('AttachmentList 지우기 버튼 설명: 버튼이 그 줄의 파일 이름으로 설명된다', () => {
    render(list({ items: [item({ canDelete: true }), item({ id: 'a2', name: 'shot.png', canDelete: true })], onDelete: () => undefined }));

    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(describedText(within(rows[1] as HTMLElement).getByRole('button', { name: '지우기' }))).toBe('shot.png');
    expect(describedText(within(rows[0] as HTMLElement).getByRole('button', { name: '지우기' }))).toBe('log.txt');
  });

  test('AttachmentList 링크 없음: href가 없으면 파일 이름을 글자로만 보인다', () => {
    render(list({ items: [{ id: 'a1', name: 'log.txt', size: 2048, uploaderLabel: '나', createdAt: T0 }] }));

    expect(screen.queryByRole('link')).toBeNull();
    expect(text(screen.getByRole('listitem'))).toContain('log.txt');
  });
});
