// screens.md 5절 8 FormErrorSummary. 칸 오류 N개를 링크 목록으로, 누르면 그 칸으로(2절 400 fields).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { FormErrorSummary } from './FormErrorSummary';

const errors = [
  { fieldId: 'title', message: '제목이 200자를 넘습니다.' },
  { fieldId: 'content', message: '내용이 10,000자를 넘습니다.' },
];

function Form(props: { serverMessage?: string }) {
  return (
    <form>
      <FormErrorSummary title="2개 칸을 고쳐 주세요" errors={errors} {...props} />
      <label htmlFor="title">제목</label>
      <input id="title" />
      <label htmlFor="content">내용</label>
      <textarea id="content" />
    </form>
  );
}

const band = () => found(() => screen.queryAllByRole('alert').find((el) => text(el).includes('2개 칸을 고쳐 주세요')), '오류 요약 띠(role="alert")');

describe('FormErrorSummary', () => {
  test('FormErrorSummary 띠: role="alert"에 제목 "2개 칸을 고쳐 주세요"와 칸 오류 링크 둘', async () => {
    render(<Form />);

    const b = await band();
    const links = within(b).getAllByRole('link');
    expect(links.map((a) => text(a))).toEqual(['제목이 200자를 넘습니다.', '내용이 10,000자를 넘습니다.']);
  });

  test('FormErrorSummary 링크: 누르면 그 칸으로 포커스가 간다', async () => {
    render(<Form />);
    const user = userEvent.setup();
    const b = await band();

    await user.click(within(b).getByRole('link', { name: '내용이 10,000자를 넘습니다.' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '내용' }));
    await user.click(within(b).getByRole('link', { name: '제목이 200자를 넘습니다.' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '제목' }));
  });

  test('FormErrorSummary 서버 문구: 화면에 없는 칸이 있으면 서버 message를 띠에 함께 보인다', async () => {
    render(<Form serverMessage="라벨 값이 틀렸습니다." />);

    expect(text(await band())).toContain('라벨 값이 틀렸습니다.');
  });

  test('FormErrorSummary 포커스: 그려져도 포커스를 가져가지 않는다(포커스는 화면이 첫 틀린 칸으로 옮긴다)', async () => {
    render(<Form />);
    const title = screen.getByRole('textbox', { name: '제목' });
    title.focus();

    await band();
    expect(document.activeElement).toBe(title);
  });

  test('FormErrorSummary axe: 링크 목록과 서버 문구에 접근성 위반이 없다', async () => {
    render(<Form serverMessage="라벨 값이 틀렸습니다." />);
    await band();

    await expectNoAxeViolations();
  });
});
