// screens.md 5절 19 StatusBadge. 값마다 색 점과 글자, 고객용 말·상담용 말 두 벌(tokens.md 1절 문의 상태 배지).
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText, text } from '../__tests__/support';
import type { IssueStatus } from '../types';
import { StatusBadge } from './StatusBadge';

const issueWords: Record<IssueStatus, { customer: string; agent: string }> = {
  RECEIVED: { customer: '접수됨', agent: '접수' },
  CONFIRMED: { customer: '확인됨', agent: '확인' },
  IN_PROGRESS: { customer: '처리 중', agent: '처리 중' },
  RESOLVED: { customer: '해결됨', agent: '해결' },
  CLOSED: { customer: '종료됨', agent: '종료' },
};

describe('StatusBadge', () => {
  for (const [status, words] of Object.entries(issueWords) as [IssueStatus, { customer: string; agent: string }][]) {
    test(`StatusBadge 문의 ${status}: 고객에게는 "${words.customer}", 상담 쪽에는 "${words.agent}"`, () => {
      const customer = render(<StatusBadge kind="issue" status={status} audience="customer" />);
      expect(spokenText(customer.container)).toBe(words.customer);
      customer.unmount();

      const agent = render(<StatusBadge kind="issue" status={status} audience="agent" />);
      expect(spokenText(agent.container)).toBe(words.agent);
      expect(text(agent.container)).not.toContain(status);
    });
  }

  test('StatusBadge 고객사 상태: ACTIVE 사용 중, SUSPENDED 멈춤, ARCHIVED 보관', () => {
    const words = { ACTIVE: '사용 중', SUSPENDED: '멈춤', ARCHIVED: '보관' } as const;
    for (const [status, word] of Object.entries(words) as [keyof typeof words, string][]) {
      const { container, unmount } = render(<StatusBadge kind="project" status={status} />);
      expect(spokenText(container), status).toBe(word);
      unmount();
    }
  });

  test('StatusBadge 계정 상태: 값 이름(ACTIVE·INVITED·SUSPENDED)이 아니라 한국어 글자를 보이고 셋이 서로 다르다', () => {
    const shown = (['ACTIVE', 'INVITED', 'SUSPENDED'] as const).map((status) => {
      const { container, unmount } = render(<StatusBadge kind="account" status={status} />);
      const t = spokenText(container);
      unmount();
      expect(t, status).not.toBe('');
      expect(t, status).not.toContain(status);
      return t;
    });

    expect(new Set(shown).size).toBe(3);
  });

  test('StatusBadge 점: 색 점은 장식이라 읽지 않는다(aria-hidden), 글자만 읽는다', () => {
    const { container } = render(<StatusBadge kind="issue" status="RESOLVED" audience="customer" />);

    expect(container.querySelectorAll('[aria-hidden="true"]').length, '색 점(aria-hidden)').toBeGreaterThan(0);
    expect(spokenText(container)).toBe('해결됨');
  });

  test('StatusBadge axe: 세 종류에 접근성 위반이 없다', async () => {
    render(
      <div>
        <StatusBadge kind="issue" status="IN_PROGRESS" audience="customer" />
        <StatusBadge kind="issue" status="CLOSED" audience="agent" />
        <StatusBadge kind="project" status="ACTIVE" />
        <StatusBadge kind="account" status="INVITED" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
