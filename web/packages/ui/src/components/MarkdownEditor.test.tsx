// screens.md 5절 23 MarkdownEditor. 툴바(굵게, 기울임, 취소선, 제목, 글머리 목록, 번호 목록, 인용, 코드, 링크),
// 쓰기·미리 보기 탭. 쓰기, 미리 보기, 포커스, 오류, 막힘, 내부 모드(C-4). 글자 수 표시. 이미지 버튼은 ATT-6 묶음.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations, found, text } from '../__tests__/support';
import { MarkdownEditor, type MarkdownEditorProps } from './MarkdownEditor';

const TOOLS = ['굵게', '기울임', '취소선', '제목', '글머리 목록', '번호 목록', '인용', '코드', '링크'];

function Controlled(props: Partial<Omit<MarkdownEditorProps, 'value' | 'onChange'>> & { initial?: string }) {
  const { initial, ...rest } = props;
  const [value, setValue] = useState(initial ?? '');
  return <MarkdownEditor label="내용" minHeight={420} {...rest} value={value} onChange={setValue} />;
}

const area = () => found(() => screen.queryByRole('textbox', { name: '내용' }), '편집칸 "내용"');
const tool = (name: string) => found(() => screen.queryByRole('button', { name }), `툴바 버튼 "${name}"`);

describe('MarkdownEditor', () => {
  test('MarkdownEditor 툴바: 아홉 버튼이 있고 이미지 버튼은 없다', async () => {
    render(<Controlled />);

    for (const name of TOOLS) await tool(name);
    expect(screen.queryByRole('button', { name: /이미지/ })).toBeNull();
  });

  test('MarkdownEditor tools: 받은 도구만 보인다', async () => {
    render(<Controlled tools={['bold', 'link']} />);

    await tool('굵게');
    await tool('링크');
    expect(screen.queryByRole('button', { name: '인용' })).toBeNull();
  });

  test('MarkdownEditor 굵게: 고른 글자를 **로 감싼다', async () => {
    render(<Controlled initial="로그인이 안 됩니다" />);
    const user = userEvent.setup();
    const t = (await area()) as HTMLTextAreaElement;

    t.focus();
    t.setSelectionRange(0, 3);
    await user.click(await tool('굵게'));
    expect(t).toHaveValue('**로그인**이 안 됩니다');
  });

  test('MarkdownEditor 글머리 목록: 지금 줄 앞에 "- "를 붙인다', async () => {
    render(<Controlled initial="첫 줄" />);
    const user = userEvent.setup();
    const t = (await area()) as HTMLTextAreaElement;

    t.focus();
    t.setSelectionRange(1, 1);
    await user.click(await tool('글머리 목록'));
    expect(t).toHaveValue('- 첫 줄');
  });

  test('MarkdownEditor 쓰기·미리 보기: 탭 두 개, 미리 보기를 누르면 마크다운을 그려 보이고 쓰기로 돌아오면 편집칸', async () => {
    render(<Controlled initial="**굵은 글**" />);
    const user = userEvent.setup();
    await area();

    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    expect(write).toHaveAttribute('aria-selected', 'true');
    await user.click(await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"'));

    await found(() => Array.from(document.querySelectorAll('strong')).find((el) => text(el) === '굵은 글'), '미리 보기의 굵게');
    expect(screen.queryByRole('tab', { name: '미리 보기' })).toHaveAttribute('aria-selected', 'true');
    await user.click(write);
    expect(await area()).toHaveValue('**굵은 글**');
  });

  test('MarkdownEditor 제어 모드: mode를 주면 따르고 탭을 누르면 onModeChange', async () => {
    const onModeChange = vi.fn();
    render(<Controlled mode="write" onModeChange={onModeChange} />);
    const user = userEvent.setup();

    await user.click(await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"'));
    expect(onModeChange).toHaveBeenCalledWith('preview');
  });

  test('MarkdownEditor 탭 키보드: 쓰기·미리 보기는 좌우 화살표로 옮긴다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();

    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    write.focus();
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(screen.queryByRole('tab', { name: '미리 보기' }));
  });

  test('MarkdownEditor Ctrl+Enter: 편집칸에서 onSubmitShortcut을 부르고 줄을 더하지 않는다', async () => {
    const onSubmitShortcut = vi.fn();
    render(<Controlled initial="답변" onSubmitShortcut={onSubmitShortcut} />);
    const user = userEvent.setup();
    const t = await area();

    t.focus();
    await user.keyboard('{Control>}{Enter}{/Control}');
    expect(onSubmitShortcut).toHaveBeenCalledTimes(1);
    expect(t).toHaveValue('답변');
  });

  test('MarkdownEditor Cmd+Enter(맥): onSubmitShortcut', async () => {
    const onSubmitShortcut = vi.fn();
    render(<Controlled onSubmitShortcut={onSubmitShortcut} />);
    const user = userEvent.setup();

    (await area()).focus();
    await user.keyboard('{Meta>}{Enter}{/Meta}');
    expect(onSubmitShortcut).toHaveBeenCalledTimes(1);
  });

  test('MarkdownEditor Tab: 편집칸 안에서 Tab은 들여쓰기가 아니라 칸을 떠난다', async () => {
    render(
      <>
        <Controlled initial="본문" />
        <button type="button">보내기</button>
      </>,
    );
    const user = userEvent.setup();
    const t = await area();

    t.focus();
    await user.tab();
    expect(t).toHaveValue('본문');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '보내기' }));
  });

  test('MarkdownEditor 글자 수: maxLength와 showCount면 "N / 최대"를 보인다', async () => {
    render(<Controlled maxLength={4000} showCount initial="확인" />);
    const user = userEvent.setup();

    await user.type(await area(), '했습니다');
    expect(text(document.body)).toMatch(/6\s*\/\s*4,?000/);
  });

  test('MarkdownEditor 오류: aria-invalid와 오류·도움말 문구를 aria-describedby로', async () => {
    render(<Controlled error="4,000자를 넘었습니다." helpText="마크다운을 쓸 수 있습니다" />);

    const t = await area();
    expect(t).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(t)).toContain('4,000자를 넘었습니다.');
    expect(describedText(t)).toContain('마크다운을 쓸 수 있습니다');
  });

  test('MarkdownEditor 막힘: 편집칸과 툴바 버튼을 막는다', async () => {
    render(<Controlled disabled />);

    expect(await area()).toBeDisabled();
    expect(await tool('굵게')).toBeDisabled();
  });

  test('MarkdownEditor 최소 높이: minHeight를 편집칸 최소 높이로 쓴다', async () => {
    render(<Controlled minHeight={112} />);

    expect((await area()).style.minHeight).toBe('112px');
  });

  test('MarkdownEditor axe: 쓰기·오류·내부 모드에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Controlled />
        <MarkdownEditor label="댓글" minHeight={112} value="" onChange={() => undefined} internal error="빈 내용은 보낼 수 없습니다." />
      </div>,
    );
    await area();

    await expectNoAxeViolations();
  });
});
