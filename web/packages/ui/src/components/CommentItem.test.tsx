// screens.md 5절 25 CommentItem. 공개, 내부(칸 전체 internal-bg, 머리에 자물쇠와 "내부 메모"), 지워짐(흔적).
// 쓴 사람(포털에서 상담 쪽은 "지원팀"), 시각. 본문은 마크다운 보기.
import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, spokenText, text } from '../__tests__/support';
import { CommentItem, type CommentItemProps } from './CommentItem';

const T0 = '2026-10-08T03:15:00Z';

function comment(over: Partial<CommentItemProps> = {}) {
  return (
    <CommentItem
      id="comment-101"
      author={{ kind: 'person', name: '김상담' }}
      visibility="public"
      createdAt={T0}
      body="확인하고 있습니다."
      supportLabel="지원팀"
      meLabel="나"
      internalLabel="내부 메모"
      deletedLabel="지워진 댓글"
      {...over}
    />
  );
}

const item = () => found(() => screen.queryByRole('article'), '댓글 article');

describe('CommentItem', () => {
  test('CommentItem 공개: 쓴 사람, 본문, 올린 시각 <time datetime>', async () => {
    render(comment());

    const a = await item();
    expect(text(a)).toContain('김상담');
    expect(text(a)).toContain('확인하고 있습니다.');
    expect(a.querySelector('time')?.getAttribute('datetime')).toBe(T0);
    expect(text(a)).not.toContain('내부 메모');
  });

  test('CommentItem 나: 내 댓글은 이름 대신 "나"', async () => {
    render(comment({ author: { kind: 'person', name: '김고객', isMe: true } }));

    const a = await item();
    expect(text(a)).toContain('나');
    expect(text(a)).not.toContain('김고객');
  });

  test('CommentItem 지원팀: 포털에서 상담 쪽 댓글은 "지원팀"', async () => {
    render(comment({ author: { kind: 'support' } }));

    expect(text(await item())).toContain('지원팀');
  });

  test('CommentItem 내부: 머리에 "내부 메모" 글자와 읽지 않는 자물쇠 아이콘', async () => {
    render(comment({ visibility: 'internal', body: '고객사에 전화해 봤습니다.' }));

    const a = await item();
    const head = a.querySelector('header') ?? a;
    expect(spokenText(head)).toContain('내부 메모');
    expect(head.querySelectorAll('[aria-hidden="true"]').length, '자물쇠 아이콘(aria-hidden)').toBeGreaterThan(0);
    expect(text(a)).toContain('고객사에 전화해 봤습니다.');
  });

  test('CommentItem 지워짐: 본문 대신 "지워진 댓글" 흔적', async () => {
    render(comment({ deleted: true, body: '지운 내용' }));

    const a = await item();
    expect(text(a)).toContain('지워진 댓글');
    expect(text(a)).not.toContain('지운 내용');
  });

  test('CommentItem 마크다운: 본문을 마크다운 보기로 그린다(굵게, 새 탭 링크)', async () => {
    render(comment({ body: '**재현 순서**는 [안내](https://example.com/help)를 보세요.' }));

    const a = await item();
    const strong = Array.from(a.querySelectorAll('strong')).find((el) => text(el) === '재현 순서');
    expect(strong, '굵게').toBeDefined();
    const link = await found(() => screen.queryByRole('link', { name: '안내' }), '본문 링크');
    expect(link).toHaveAttribute('target', '_blank');
    expect(text(a)).not.toContain('**');
  });

  test('CommentItem 첨부: 댓글의 첨부를 댓글 안에 그린다', async () => {
    render(comment({ attachments: <ul><li><a href="/files/1">trace.log</a></li></ul> }));

    const a = await item();
    expect(a.contains(screen.getByRole('link', { name: 'trace.log' }))).toBe(true);
  });

  test('CommentItem 포커스 대상: id와 tabindex=-1, ref로 포커스할 수 있다', async () => {
    const ref = createRef<HTMLElement>();
    render(comment({ ref }));

    const a = await item();
    expect(a).toHaveAttribute('id', 'comment-101');
    expect(a).toHaveAttribute('tabindex', '-1');
    ref.current?.focus();
    expect(document.activeElement).toBe(a);
  });

  test('CommentItem axe: 공개·내부·지워짐에 접근성 위반이 없다', async () => {
    render(
      <div>
        {comment()}
        {comment({ id: 'comment-102', visibility: 'internal', body: '내부 메모입니다' })}
        {comment({ id: 'comment-103', deleted: true })}
      </div>,
    );
    await found(() => screen.queryAllByRole('article')[2], '세 번째 댓글');

    await expectNoAxeViolations();
  });
});
