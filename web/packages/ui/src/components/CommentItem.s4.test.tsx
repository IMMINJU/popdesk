// 4번 묶음 screens.md 2절·3절·4절 CommentItem: 고침 표시, 동작 자리, 멘션 줄, 지운 댓글의 지운 사람과 시각, 고치는 중.
// 새 props는 선택이다. 지운 댓글에는 동작, 편집기, 멘션 줄을 그리지 않는다(4절: CommentItem이 거른다).
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { commentActionDescribedBy } from '../aria';
import { describedText, expectNoAxeViolations, must, text } from '../__tests__/support';
import { CommentItem, type CommentItemProps } from './CommentItem';

const WRITTEN = '2026-10-08T03:15:00Z';
const EDITED = '2026-10-09T01:02:03Z';
const DELETED = '2026-10-10T04:05:06Z';
const ID = 'comment-5';

function item(over: Partial<CommentItemProps> = {}) {
  return (
    <CommentItem
      id={ID}
      author={{ kind: 'person', name: '이상담' }}
      visibility="public"
      createdAt={WRITTEN}
      body="확인하고 있습니다"
      internalLabel="내부 메모"
      deletedLabel="지워진 댓글"
      editedLabel="고침"
      mentionsLabel="알림"
      {...over}
    />
  );
}

const actions = (
  <>
    <button type="button" aria-describedby={commentActionDescribedBy(ID)}>
      댓글 고치기
    </button>
    <button type="button" aria-describedby={commentActionDescribedBy(ID)}>
      댓글 지우기
    </button>
  </>
);

const article = () => screen.getByRole('article');
const timeAt = (root: Element, at: string) => Array.from(root.querySelectorAll('time')).find((t) => t.getAttribute('datetime') === at) ?? null;

describe('CommentItem 4번 묶음', () => {
  test('S4-AC-61 CommentItem 고침: editedAt을 주면 쓴 시각 뒤에 "고침"과 고친 시각이 있고, 정확한 시각은 title에 있다. 주지 않거나 null이면 없다', () => {
    const { rerender } = render(item({ editedAt: EDITED }));

    const head = must(article().querySelector('header'), '댓글 머리');
    expect(text(head)).toContain('고침');
    const edited = must(timeAt(head, EDITED), '고친 시각 <time datetime>');
    expect(edited.getAttribute('title') ?? '', '정확한 시각(title)').toMatch(/2026/);
    expect(must(timeAt(head, WRITTEN), '쓴 시각').compareDocumentPosition(edited) & Node.DOCUMENT_POSITION_FOLLOWING, '쓴 시각 뒤').toBeTruthy();
    // The edited time adds no Tab stop to the comment.
    expect(edited).not.toHaveAttribute('tabindex');

    rerender(item({ editedAt: null }));
    expect(text(article())).not.toContain('고침');
    expect(timeAt(article(), EDITED)).toBeNull();
  });

  test('S4-AC-60 CommentItem 동작: actions의 버튼이 머리 줄에 있고, aria-describedby로 그 댓글의 쓴 사람과 쓴 시각에 이어진다', () => {
    render(item({ actions }));

    const head = must(article().querySelector('header'), '댓글 머리');
    for (const name of ['댓글 고치기', '댓글 지우기']) {
      const button = within(head).getByRole('button', { name });
      expect(describedText(button), `"${name}"의 설명`).toContain('이상담');
      const described = (button.getAttribute('aria-describedby') ?? '').split(/\s+/).map((id) => document.getElementById(id));
      expect(described.every((el) => el !== null && article().contains(el)), '설명이 이 댓글 안에 있다').toBe(true);
      expect(described.some((el) => el !== null && timeAt(el, WRITTEN) !== null), '설명에 쓴 시각').toBe(true);
      // The description is the author and the time, not the button's own text.
      expect(describedText(button)).not.toContain('댓글 고치기');
    }
  });

  test('S4-AC-63 CommentItem 멘션: mentions를 주면 본문 아래에 "알림: 김상담, 이관리" 한 줄이 있다. 비었으면 줄이 없다', () => {
    const { rerender } = render(item({ mentions: ['김상담', '이관리'] }));

    const line = must(
      Array.from(article().querySelectorAll('p')).find((p) => text(p) === '알림: 김상담, 이관리'),
      '멘션 줄',
    );
    const body = screen.getByText('확인하고 있습니다');
    expect(body.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING, '본문 아래').toBeTruthy();
    expect(line.querySelector('img'), '사진은 그리지 않는다').toBeNull();

    rerender(item({ mentions: [] }));
    expect(text(article())).not.toContain('알림:');
  });

  test('S4-AC-62 CommentItem 지운 댓글: "지워진 댓글" 옆에 지운 사람과 지운 시각이 있다', () => {
    render(item({ deleted: true, body: '', deletedByLabel: '최관리 님이 지움', deletedAt: DELETED }));

    expect(text(article())).toContain('지워진 댓글');
    expect(text(article())).toContain('최관리 님이 지움');
    expect(must(timeAt(article(), DELETED), '지운 시각 <time datetime>').getAttribute('title') ?? '').toMatch(/2026/);
    expect(text(article())).toContain('이상담');
  });

  test('S4-AC-62 CommentItem 지운 댓글: 동작, 편집기, 멘션 줄을 받아도 그리지 않는다', () => {
    render(
      item({
        deleted: true,
        body: '',
        actions,
        editing: <textarea aria-label="댓글 내용 고치기" defaultValue="쓰던 글" />,
        mentions: ['김상담'],
      }),
    );

    expect(text(article())).toContain('지워진 댓글');
    expect(within(article()).queryAllByRole('button')).toHaveLength(0);
    expect(within(article()).queryAllByRole('textbox')).toHaveLength(0);
    expect(text(article())).not.toContain('알림:');
  });

  test('S4-AC-61 CommentItem 고치는 중: editing을 주면 본문 대신 그것을 그린다', () => {
    render(
      item({
        editing: (
          <form aria-label="댓글 고치기">
            <textarea aria-label="댓글 내용 고치기" defaultValue="확인하고 있습니다" />
          </form>
        ),
      }),
    );

    const form = within(article()).getByRole('form', { name: '댓글 고치기' });
    expect(within(form).getByRole('textbox', { name: '댓글 내용 고치기' })).toHaveValue('확인하고 있습니다');
    // The body is not drawn beside the editor: the text is in the editor only.
    expect(screen.queryByText('확인하고 있습니다', { selector: 'p' })).toBeNull();
    expect(text(article())).toContain('이상담');
  });

  test.each([
    ['고침·동작·멘션', { editedAt: EDITED, actions, mentions: ['김상담', '이관리'] }],
    ['내부 메모의 동작', { visibility: 'internal' as const, actions }],
    ['지운 댓글', { deleted: true, body: '', deletedByLabel: '최관리 님이 지움', deletedAt: DELETED }],
  ])('CommentItem axe (%s): 접근성 위반이 없다', async (_, over) => {
    render(item(over));

    expect(article()).toBeInTheDocument();
    await expectNoAxeViolations();
  });
});
