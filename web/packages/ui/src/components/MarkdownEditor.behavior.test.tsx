// screens.md 5절 23 MarkdownEditor. C-10 변이 테스트가 찾은 빈칸: 굵게·글머리 목록 밖의 도구 일곱,
// 여러 줄 선택, 도구를 쓴 뒤 커서, 툴바 안 화살표 이동(Tab 자리 하나), 탭 키보드의 나머지 키,
// 탭과 패널의 연결, 빈 미리 보기, ref.
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { found, text } from '../__tests__/support';
import { MarkdownEditor, type MarkdownEditorProps } from './MarkdownEditor';

function Controlled(props: Partial<Omit<MarkdownEditorProps, 'value' | 'onChange'>> & { initial?: string }) {
  const { initial, ...rest } = props;
  const [value, setValue] = useState(initial ?? '');
  return <MarkdownEditor label="내용" minHeight={420} {...rest} value={value} onChange={setValue} />;
}

const area = async () => (await found(() => screen.queryByRole('textbox', { name: '내용' }), '편집칸 "내용"')) as HTMLTextAreaElement;
const tool = (name: string) => found(() => screen.queryByRole('button', { name }), `툴바 버튼 "${name}"`);
const toolbar = () => found(() => screen.queryByRole('toolbar'), '툴바');

/** Selects `from..to` in the text area and presses the tool. */
async function useTool(name: string, initial: string, from: number, to: number) {
  render(<Controlled initial={initial} />);
  const user = userEvent.setup();
  const t = await area();
  t.focus();
  t.setSelectionRange(from, to);
  await user.click(await tool(name));
  return t;
}

/** The tool puts the cursor back on the next frame. */
async function selection(t: HTMLTextAreaElement, want: [number, number]) {
  await waitFor(() => {
    expect([t.selectionStart, t.selectionEnd]).toEqual(want);
  });
  expect(document.activeElement).toBe(t);
}

describe('MarkdownEditor 도구', () => {
  test('MarkdownEditor 굵게: 감싼 뒤 원래 글자를 다시 고른다', async () => {
    const t = await useTool('굵게', '로그인이 안 됩니다', 0, 3);

    expect(t).toHaveValue('**로그인**이 안 됩니다');
    await selection(t, [2, 5]);
  });

  test('MarkdownEditor 기울임: 고른 글자를 *로 감싼다', async () => {
    const t = await useTool('기울임', '아주 급함', 3, 5);

    expect(t).toHaveValue('아주 *급함*');
    await selection(t, [4, 6]);
  });

  test('MarkdownEditor 취소선: 고른 글자를 ~~로 감싼다', async () => {
    const t = await useTool('취소선', '옛 주소', 0, 1);

    expect(t).toHaveValue('~~옛~~ 주소');
  });

  test('MarkdownEditor 제목: 고른 줄마다 앞에 "## "', async () => {
    const t = await useTool('제목', '하나\n둘\n셋', 3, 4);

    expect(t).toHaveValue('하나\n## 둘\n셋');
    await selection(t, [6, 7]);
  });

  test('MarkdownEditor 글머리 목록: 여러 줄을 고르면 줄마다 "- "', async () => {
    const t = await useTool('글머리 목록', '앞\n하나\n둘\n뒤', 2, 6);

    expect(t).toHaveValue('앞\n- 하나\n- 둘\n뒤');
    await selection(t, [4, 10]);
  });

  test('MarkdownEditor 글머리 목록: 마지막 줄도 끝까지 붙인다', async () => {
    const t = await useTool('글머리 목록', '앞\n끝 줄', 3, 3);

    expect(t).toHaveValue('앞\n- 끝 줄');
  });

  test('MarkdownEditor 번호 목록: 고른 줄에 1. 2. 차례로', async () => {
    const t = await useTool('번호 목록', '사과\n배', 0, 4);

    expect(t).toHaveValue('1. 사과\n2. 배');
  });

  test('MarkdownEditor 인용: 줄 앞에 "> "', async () => {
    const t = await useTool('인용', '오류 문구', 0, 0);

    expect(t).toHaveValue('> 오류 문구');
  });

  test('MarkdownEditor 코드: 한 줄이면 `로, 여러 줄이면 ``` 블록으로 감싼다', async () => {
    const t = await useTool('코드', 'npm run build', 0, 13);
    expect(t).toHaveValue('`npm run build`');
  });

  test('MarkdownEditor 코드: 여러 줄 글에서 한 줄 안을 고르면 `로 감싼다', async () => {
    const t = await useTool('코드', 'a\nb', 0, 1);

    expect(t).toHaveValue('`a`\nb');
  });

  test('MarkdownEditor 코드 블록: 여러 줄을 고르면 ``` 줄로 감싼다', async () => {
    const t = await useTool('코드', 'a\nb', 0, 3);

    expect(t).toHaveValue('```\na\nb\n```');
    await selection(t, [4, 7]);
  });

  test('MarkdownEditor 링크: [고른 글자](https://)를 넣고 주소 자리 "https://"를 고른다', async () => {
    const t = await useTool('링크', '설치 안내 보기', 0, 5);

    expect(t).toHaveValue('[설치 안내](https://) 보기');
    await selection(t, [8, 16]);
  });
});

describe('MarkdownEditor 툴바 키보드', () => {
  test('MarkdownEditor 툴바 Tab 자리: 처음에는 첫 버튼만 tabindex=0', async () => {
    render(<Controlled />);
    const bar = await toolbar();

    const zero = within(bar).getAllByRole('button').filter((b) => b.getAttribute('tabindex') === '0');
    expect(zero.map((b) => b.getAttribute('aria-label'))).toEqual(['굵게']);
  });

  test('MarkdownEditor 툴바 화살표: 오른쪽·왼쪽으로 옮기고 양 끝에서 돌아가며, Home·End는 처음·끝', async () => {
    render(<Controlled />);
    const user = userEvent.setup();
    const bold = await tool('굵게');

    bold.focus();
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(await tool('기울임'));
    expect(await tool('기울임')).toHaveAttribute('tabindex', '0');
    expect(bold).toHaveAttribute('tabindex', '-1');

    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(document.activeElement).toBe(await tool('링크'));
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(bold);
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(await tool('링크'));
    await user.keyboard('{Home}');
    expect(document.activeElement).toBe(bold);
  });

  test('MarkdownEditor 툴바 다른 키: 화살표·Home·End가 아니면 움직이지 않는다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();
    const bold = await tool('굵게');

    bold.focus();
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(bold);
    expect(bold).toHaveAttribute('tabindex', '0');
  });

  test('MarkdownEditor 툴바 누름: 누른 버튼이 다음 Tab 자리가 된다', async () => {
    render(<Controlled initial="글" />);
    const user = userEvent.setup();

    await user.click(await tool('인용'));
    expect(await tool('인용')).toHaveAttribute('tabindex', '0');
    expect(await tool('굵게')).toHaveAttribute('tabindex', '-1');
  });

  test('MarkdownEditor 툴바 누름: 마우스로 눌러도 편집칸의 선택을 빼앗지 않는다', async () => {
    render(<Controlled initial="로그인" />);
    const t = await area();
    t.focus();
    t.setSelectionRange(0, 3);

    const notPrevented = (await tool('굵게')).dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(notPrevented).toBe(false);
  });

  test('MarkdownEditor 툴바 이름: 기본 "서식"이고 편집칸을 가리킨다', async () => {
    render(<Controlled />);
    const bar = await toolbar();

    expect(bar).toHaveAccessibleName('서식');
    expect(bar.getAttribute('aria-controls')).toBe((await area()).id);
  });

  test('MarkdownEditor 툴바 없음: tools가 비면 툴바를 그리지 않는다', async () => {
    render(<Controlled tools={[]} />);
    await area();

    expect(screen.queryByRole('toolbar')).toBeNull();
  });
});

describe('MarkdownEditor 탭과 미리 보기', () => {
  test('MarkdownEditor 탭 키보드: 왼쪽 화살표도 옮기고, End는 미리 보기, Home은 쓰기, 다른 키는 그대로', async () => {
    render(<Controlled />);
    const user = userEvent.setup();
    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    const preview = await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"');

    write.focus();
    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(preview);
    expect(preview).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Home}');
    expect(document.activeElement).toBe(write);
    expect(write).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(preview);
    await user.keyboard('a');
    expect(document.activeElement).toBe(preview);
    expect(preview).toHaveAttribute('aria-selected', 'true');
  });

  test('MarkdownEditor 탭 Tab 자리: 고른 탭만 tabindex=0', async () => {
    render(<Controlled />);
    const user = userEvent.setup();
    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    const preview = await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"');

    expect([write.tabIndex, preview.tabIndex]).toEqual([0, -1]);
    await user.click(preview);
    expect([write.tabIndex, preview.tabIndex]).toEqual([-1, 0]);
  });

  test('MarkdownEditor 탭과 패널: 탭이 aria-controls로 패널을 가리키고 패널 이름은 탭 글자', async () => {
    render(<Controlled initial="본문" />);
    const user = userEvent.setup();
    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    const preview = await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"');

    expect(write.getAttribute('aria-controls')).toBe(screen.getByRole('tabpanel', { name: '쓰기' }).id);
    await user.click(preview);
    expect(preview.getAttribute('aria-controls')).toBe(screen.getByRole('tabpanel', { name: '미리 보기' }).id);
    expect(write.getAttribute('aria-controls')).not.toBe(preview.getAttribute('aria-controls'));
    expect([write.getAttribute('aria-controls'), preview.getAttribute('aria-controls')]).not.toContain('');
  });

  test('MarkdownEditor 탭 Home·End: 쓰기에서 Home은 쓰기에, 미리 보기에서 End는 미리 보기에 머문다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();
    const write = await found(() => screen.queryByRole('tab', { name: '쓰기' }), '탭 "쓰기"');
    const preview = await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"');

    write.focus();
    await user.keyboard('{Home}');
    expect(document.activeElement).toBe(write);
    expect(write).toHaveAttribute('aria-selected', 'true');

    await user.click(preview);
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(preview);
    expect(preview).toHaveAttribute('aria-selected', 'true');
  });

  test('MarkdownEditor 미리 보기 중: 편집칸과 툴바를 숨긴다', async () => {
    render(<Controlled initial="본문" />);
    const user = userEvent.setup();

    await user.click(await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"'));
    expect(screen.queryByRole('textbox', { name: '내용' })).toBeNull();
    expect(screen.queryByRole('toolbar')).toBeNull();
  });

  test('MarkdownEditor 빈 미리 보기: 빈칸뿐이면 "미리 볼 내용이 없습니다", emptyPreviewText를 주면 그것', async () => {
    const { unmount } = render(<Controlled initial={'  \n '} mode="preview" />);
    expect(text(await found(() => screen.queryByRole('tabpanel', { name: '미리 보기' }), '미리 보기 패널'))).toBe('미리 볼 내용이 없습니다');
    unmount();

    render(<Controlled initial="" mode="preview" emptyPreviewText="쓴 내용이 없습니다" />);
    expect(text(await found(() => screen.queryByRole('tabpanel', { name: '미리 보기' }), '미리 보기 패널'))).toBe('쓴 내용이 없습니다');
  });

  test('MarkdownEditor 미리 보기: 앞뒤에 빈칸이 있어도 내용이 있으면 그린다', async () => {
    render(<Controlled initial={' **굵게** '} mode="preview" />);

    const panel = await found(() => screen.queryByRole('tabpanel', { name: '미리 보기' }), '미리 보기 패널');
    expect(text(panel.querySelector('strong'))).toBe('굵게');
  });
});

describe('MarkdownEditor 그 밖', () => {
  test('MarkdownEditor Enter만: 수정 키 없이 Enter는 줄을 바꾸고 onSubmitShortcut을 부르지 않는다', async () => {
    const onSubmitShortcut = vi.fn();
    render(<Controlled initial="답변" onSubmitShortcut={onSubmitShortcut} />);
    const user = userEvent.setup();
    const t = await area();

    await user.type(t, '{Enter}');
    expect(onSubmitShortcut).not.toHaveBeenCalled();
    expect(t).toHaveValue('답변\n');
  });

  test('MarkdownEditor Ctrl 다른 키: Ctrl+A 같은 다른 조합은 onSubmitShortcut을 부르지 않는다', async () => {
    const onSubmitShortcut = vi.fn();
    render(<Controlled initial="답변" onSubmitShortcut={onSubmitShortcut} />);
    const user = userEvent.setup();

    (await area()).focus();
    await user.keyboard('{Control>}a{/Control}');
    expect(onSubmitShortcut).not.toHaveBeenCalled();
  });

  test('MarkdownEditor 글자 수: showCount나 maxLength 하나만 있으면 세지 않는다', () => {
    const { container, rerender } = render(<MarkdownEditor label="내용" minHeight={112} value="확인" onChange={() => undefined} showCount />);
    expect(text(container)).not.toMatch(/\d+ \//);

    rerender(<MarkdownEditor label="내용" minHeight={112} value="확인" onChange={() => undefined} maxLength={4000} />);
    expect(text(container)).not.toMatch(/\d+ \//);
  });

  test('MarkdownEditor ref: 함수 ref와 객체 ref 모두 편집칸을 받는다', async () => {
    const objectRef = createRef<HTMLTextAreaElement>();
    const seen: (HTMLTextAreaElement | null)[] = [];
    render(
      <div>
        <MarkdownEditor label="내용" minHeight={112} value="" onChange={() => undefined} ref={objectRef} />
        <MarkdownEditor
          label="댓글"
          minHeight={112}
          value=""
          onChange={() => undefined}
          ref={(el) => {
            seen.push(el);
          }}
        />
      </div>,
    );

    expect(objectRef.current).toBe(await area());
    expect(seen[0]).toBe(screen.getByRole('textbox', { name: '댓글' }));
  });
});
