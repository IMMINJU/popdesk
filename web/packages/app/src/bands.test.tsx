// screens.md 2절 저장 실패의 띠: 401은 새 탭의 로그인 링크와 함께, 403·400(칸 없음)·409·5xx는 앱의 문구로, 429는 초를 센다.
// 400(칸 있음)은 요약 띠가 맡는다. 문구는 앱이 인자로 준다.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FieldSummary, RateLimitBand, SaveFailure, type RateLimitWords, type SaveFailureWords } from './bands';
import type { Failure } from './result';

const failureText = vi.fn<SaveFailureWords['failureText']>((failure, known) => `실패:${failure.kind}:${Object.keys(known ?? {}).join(',')}`);
const WORDS: SaveFailureWords = { openLogin: '새 탭에서 로그인', saveSessionEnded: '로그인이 끝났습니다', failureText };
const RATE: RateLimitWords = { canRetry: '다시 보낼 수 있습니다', rateLimitedText: (seconds) => `${String(seconds)}초 뒤에 다시 하세요` };

describe('SaveFailure', () => {
  it.each<[string, Failure | null]>([
    ['실패 없음', null],
    ['400 칸 있음(요약 띠가 맡는다)', { kind: 'invalid', fields: [{ field: 'title', code: 'REQUIRED' }], message: 'm' }],
    ['429(초 세는 띠가 맡는다)', { kind: 'rateLimited', seconds: 5 }],
  ])('screens.md 2절 SaveFailure %s: 아무것도 그리지 않는다', (_, failure) => {
    const { container } = render(<SaveFailure failure={failure} words={WORDS} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('screens.md 2절 SaveFailure 401: 세션이 끝났다는 오류 띠와 새 탭으로 여는 로그인 링크', () => {
    render(<SaveFailure failure={{ kind: 'unauthenticated' }} words={WORDS} />);

    const band = screen.getByRole('alert');
    expect(band).toHaveTextContent('로그인이 끝났습니다');
    expect(band).toHaveAttribute('data-tone', 'error');
    const link = within(band).getByRole('link', { name: '새 탭에서 로그인' });
    expect(link).toHaveAttribute('href', '/login');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener');
    expect(failureText).not.toHaveBeenCalled();
  });

  it.each<[Failure]>([
    [{ kind: 'forbidden', code: 'FORBIDDEN' }],
    [{ kind: 'notFound' }],
    [{ kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: 'm' }],
    [{ kind: 'tooLarge' }],
    [{ kind: 'server' }],
    [{ kind: 'network' }],
  ])('screens.md 2절 SaveFailure %o: 앱의 문구를 오류 띠로 보이고 링크는 없다', (failure) => {
    const known = { ISSUE_CLOSED: '닫힌 문의입니다' };
    render(<SaveFailure failure={failure} known={known} words={WORDS} />);

    const band = screen.getByRole('alert');
    expect(band).toHaveTextContent(`실패:${failure.kind}:ISSUE_CLOSED`);
    expect(band).toHaveAttribute('data-tone', 'error');
    expect(failureText).toHaveBeenLastCalledWith(failure, known);
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe('RateLimitBand', () => {
  it('screens.md 2절 429 세는 중: 화면에는 남은 초, 읽어 주는 글은 처음 초다', () => {
    render(<RateLimitBand active={true} left={7} initial={30} ended={false} words={RATE} />);

    const band = screen.getByRole('alert');
    expect(band).toHaveAttribute('data-tone', 'error');
    const shown = within(band).getByText('7초 뒤에 다시 하세요');
    const spoken = within(band).getByText('30초 뒤에 다시 하세요');
    expect(shown).toHaveAttribute('aria-hidden', 'true');
    expect(spoken).not.toHaveAttribute('aria-hidden');
    expect(spoken).toHaveClass('pd-sr-only');
    expect(screen.queryByText('다시 보낼 수 있습니다')).toBeNull();
  });

  it('screens.md 2절 429 끝남: 다시 보낼 수 있다는 안내 하나(공손한 알림)', () => {
    render(<RateLimitBand active={false} left={0} initial={30} ended={true} words={RATE} />);

    const band = screen.getByRole('status');
    expect(band).toHaveTextContent('다시 보낼 수 있습니다');
    expect(band).toHaveAttribute('data-tone', 'info');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('screens.md 2절 429 다시 세는 중: 끝남 표시가 남아 있어도 세는 띠만 보인다', () => {
    render(<RateLimitBand active={true} left={3} initial={3} ended={true} words={RATE} />);

    expect(screen.getByRole('alert')).toHaveTextContent('3초 뒤에 다시 하세요');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('screens.md 2절 429 아님: 아무것도 그리지 않는다', () => {
    const { container } = render(<RateLimitBand active={false} left={0} initial={0} ended={false} words={RATE} />);

    expect(container).toBeEmptyDOMElement();
  });
});

describe('FieldSummary', () => {
  const items = [
    { fieldId: 'issue-title', message: '제목을 넣으세요' },
    { fieldId: 'issue-email', message: '이메일 꼴이 아닙니다' },
  ];

  it('screens.md 2절 400 요약 없음: 아무것도 그리지 않는다', () => {
    const { container } = render(<FieldSummary summary={null} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('screens.md 2절 400 요약: 제목과 칸마다 그 칸으로 가는 링크를 폼 순서로 보인다', () => {
    render(<FieldSummary summary={{ title: '2개 칸을 고쳐 주세요', items }} />);

    const band = screen.getByRole('alert');
    expect(band).toHaveTextContent('2개 칸을 고쳐 주세요');
    const links = within(band).getAllByRole('link');
    expect(links.map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['제목을 넣으세요', '#issue-title'],
      ['이메일 꼴이 아닙니다', '#issue-email'],
    ]);
    expect(band.querySelectorAll('p')).toHaveLength(1);
  });

  it('screens.md 2절 400 요약: 화면에 없는 칸이 있었으면 서버의 글도 보인다', () => {
    render(<FieldSummary summary={{ title: '1개 칸을 고쳐 주세요', items: [], serverMessage: '고객사를 고르세요' }} />);

    const band = screen.getByRole('alert');
    expect(band).toHaveTextContent('1개 칸을 고쳐 주세요');
    expect(band).toHaveTextContent('고객사를 고르세요');
    expect(within(band).queryByRole('link')).toBeNull();
  });
});
