// screens.md 5절 14 IssueList (카드 목록). P-3 모바일 폭: 카드마다 링크 하나, 상태 배지와 키, 제목, 고객사·올린 시각, 비공개는 자물쇠와 숨은 글자.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, spokenText, text } from '../__tests__/support';
import { IssueList, type IssueListItem } from './IssueList';

const T0 = '2026-10-08T03:15:00Z';

function item(over: Partial<IssueListItem> = {}): IssueListItem {
  return {
    key: 'ACME-7',
    title: '로그인이 안 됩니다',
    href: '/issues/ACME-7',
    projectName: '에이크미',
    status: 'RECEIVED',
    priority: 'NORMAL',
    authorLabel: '나',
    isPrivate: false,
    createdAt: T0,
    ...over,
  };
}

const card = (key: string) => found(() => screen.queryByRole('link', { name: new RegExp(key) }), `카드 링크 ${key}`);

describe('IssueList', () => {
  test('IssueList 데이터: 카드마다 링크 하나에 키, 제목, 고객사, 상태(고객용 말), 올린 시각 <time>', async () => {
    render(<IssueList items={[item(), item({ key: 'ACME-6', title: '프린터가 안 됩니다', href: '/issues/ACME-6', status: 'IN_PROGRESS' })]} />);

    const a = await card('ACME-7');
    expect(a).toHaveAttribute('href', '/issues/ACME-7');
    for (const part of ['ACME-7', '로그인이 안 됩니다', '에이크미', '접수됨']) expect(text(a), part).toContain(part);
    expect(a.querySelector('time')?.getAttribute('datetime')).toBe(T0);
    expect(text(await card('ACME-6'))).toContain('처리 중');
  });

  test('IssueList 카드 하나는 링크 하나: 카드 안에 다른 링크·버튼이 없다', async () => {
    render(<IssueList items={[item(), item({ key: 'ACME-6', href: '/issues/ACME-6' })]} />);

    await card('ACME-6');
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    for (const a of links) {
      expect(a.querySelectorAll('a, button, input, [tabindex]:not([tabindex="-1"])')).toHaveLength(0);
    }
  });

  test('IssueList 비공개: 자물쇠 아이콘은 읽지 않고 숨은 글자 "비공개"를 읽는다', async () => {
    render(<IssueList items={[item({ isPrivate: true })]} privateLabel="비공개" />);

    const a = await card('ACME-7');
    expect(spokenText(a)).toContain('비공개');
    expect(a.querySelectorAll('[aria-hidden="true"]').length, '자물쇠 아이콘(aria-hidden)').toBeGreaterThan(0);
  });

  test('IssueList 공개 문의에는 "비공개"가 없다', async () => {
    render(<IssueList items={[item()]} privateLabel="비공개" />);

    expect(text(await card('ACME-7'))).not.toContain('비공개');
  });

  test('IssueList 불러오는 중: aria-busy이고 뼈대 카드는 읽지 않는다', () => {
    const { container } = render(<IssueList items={[]} status="loading" skeletonCount={5} />);

    expect(container.querySelector('[aria-busy="true"]'), 'aria-busy="true"').not.toBeNull();
    expect(container.querySelectorAll('[aria-hidden="true"]').length, '뼈대(aria-hidden)').toBeGreaterThan(0);
    expect(spokenText(container)).toBe('');
  });

  test('IssueList 빈: empty를 보이고 링크가 없다', () => {
    render(<IssueList items={[]} empty={<p>아직 올린 문의가 없습니다.</p>} />);

    expect(screen.getByText('아직 올린 문의가 없습니다.')).toBeInTheDocument();
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  test('IssueList axe: 데이터와 비공개 카드에 접근성 위반이 없다', async () => {
    render(<IssueList items={[item(), item({ key: 'ACME-6', href: '/issues/ACME-6', isPrivate: true })]} privateLabel="비공개" />);
    await card('ACME-6');

    await expectNoAxeViolations();
  });
});
