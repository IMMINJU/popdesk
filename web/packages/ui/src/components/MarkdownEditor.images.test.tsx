// 5번 묶음 screens.md 1절 편집기의 이미지(S5-AC-54~56). 올리기 함수(`onImageUpload`)를 받은 편집기에만 있다:
// 툴바 끝의 "이미지" 단추, 붙여 넣기, 끌어다 놓기. 올리는 중과 실패는 편집칸 아래 한 줄이다.
// 0절: 이미지 단추는 `tools`의 값이 아니고 `tools` 뒤에 붙는다. prop이 없으면 아홉 단추 그대로다.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, file, found, must, text } from '../__tests__/support';
import { MarkdownEditor, type MarkdownEditorProps } from './MarkdownEditor';

const TOOLS = ['굵게', '기울임', '취소선', '제목', '글머리 목록', '번호 목록', '인용', '코드', '링크'];
const ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const ID_2 = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e02';
const ID_3 = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e03';
const NOT_IMAGE = 'PNG, JPEG, GIF, WebP 이미지만 본문에 넣을 수 있습니다';
const DROP_HERE = '여기에 놓으면 이미지를 넣습니다';
const IMAGES_ONLY = '이미지 파일만 본문에 넣을 수 있습니다. 다른 파일은 첨부로 올리세요';

type UploadResult = { ok: true; id: string } | { ok: false; message: string };

const png = (name: string) => file(name, 1024, 'image/png');
const syntax = (name: string, id: string) => `![${name}](attachment:${id})\n`;

/** An upload the test answers when it wants. */
function deferred() {
  let settle!: (result: UploadResult) => void;
  const promise = new Promise<UploadResult>((resolve) => {
    settle = resolve;
  });
  /** Answers, and lets React draw what the answer changed. */
  const answer = async (result: UploadResult) => {
    await act(async () => {
      settle(result);
      await promise;
    });
  };
  return { promise, answer };
}

/** An `onImageUpload` that hands out one deferred upload for each call, in order. */
function uploads() {
  const pending: ReturnType<typeof deferred>[] = [];
  const onImageUpload = vi.fn<(file: File) => Promise<UploadResult>>(() => {
    const next = deferred();
    pending.push(next);
    return next.promise;
  });
  const names = () => onImageUpload.mock.calls.map(([f]) => f.name);
  /** Waits until the editor has asked for `count` uploads, and returns the last of them. */
  const started = async (count: number) => {
    await waitFor(() => {
      expect(names().length, '시작한 올리기 수').toBeGreaterThanOrEqual(count);
    });
    return pending[count - 1] as ReturnType<typeof deferred>;
  };
  return { onImageUpload, names, started };
}

type Extra = Partial<Omit<MarkdownEditorProps, 'value' | 'onChange'>> & { initial?: string; onChange?: (value: string) => void };

function Controlled(props: Extra) {
  const { initial, onChange, ...rest } = props;
  const [value, setValue] = useState(initial ?? '');
  return (
    <MarkdownEditor
      label="내용"
      minHeight={420}
      {...rest}
      value={value}
      onChange={(next) => {
        onChange?.(next);
        setValue(next);
      }}
    />
  );
}

const area = async () => (await found(() => screen.queryByRole('textbox', { name: '내용' }), '편집칸 "내용"')) as HTMLTextAreaElement;
const tool = (name: string) => found(() => screen.queryByRole('button', { name }), `툴바 버튼 "${name}"`);
/** The first "이미지" button (the axe test draws two editors). */
const imageButton = () => found(() => screen.queryAllByRole('button', { name: '이미지' })[0], '툴바 버튼 "이미지"');
const toolNames = () => within(screen.getByRole('toolbar')).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
const statusLine = () => screen.queryAllByRole('status').map((el) => text(el)).join(' ');
const alertLine = () => screen.queryAllByRole('alert').map((el) => text(el)).join(' ');

/**
 * The file input the "이미지" button opens: the nearest image file input around the
 * button. It may be hidden, so it is not looked up by a name.
 */
function imageInputOf(button: HTMLElement): HTMLInputElement | null {
  for (let el = button.parentElement; el; el = el.parentElement) {
    const input = el.querySelector<HTMLInputElement>('input[type="file"][accept*="image/png"]');
    if (input) return input;
  }
  return null;
}

async function imageInput() {
  return must(imageInputOf(await imageButton()), '"이미지" 단추와 이어진 이미지 파일 입력');
}

/** What a paste or a drop carries. jsdom has no DataTransfer, so this holds the parts a handler reads. */
function transfer(files: File[]) {
  return {
    files: Object.assign([...files], { item: (index: number) => files[index] ?? null }),
    items: files.map((f) => ({ kind: 'file', type: f.type, getAsFile: () => f })),
    types: files.length > 0 ? ['Files'] : [],
    getData: () => '',
  };
}

/** Puts the cursor at `at` in the text area. */
function cursor(t: HTMLTextAreaElement, at: number) {
  t.focus();
  t.setSelectionRange(at, at);
}

describe('MarkdownEditor 이미지 단추', () => {
  test('S5-AC-54 MarkdownEditor 이미지 단추: onImageUpload가 있으면 툴바 끝에 "이미지"가 열째로 오고, 없으면 아홉 단추 그대로다', async () => {
    const { onImageUpload } = uploads();
    const { rerender } = render(<Controlled onImageUpload={onImageUpload} />);

    await imageButton();
    expect(toolNames()).toEqual([...TOOLS, '이미지']);

    rerender(<Controlled />);
    await waitFor(() => {
      expect(toolNames()).toEqual(TOOLS);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 단추와 tools: tools 뒤에 붙고, tools가 비면 툴바도 이미지 단추도 없다', async () => {
    const { onImageUpload } = uploads();
    const { rerender } = render(<Controlled tools={['bold', 'link']} onImageUpload={onImageUpload} />);

    await imageButton();
    expect(toolNames()).toEqual(['굵게', '링크', '이미지']);

    rerender(<Controlled tools={[]} onImageUpload={onImageUpload} />);
    await waitFor(() => {
      expect(screen.queryByRole('toolbar')).toBeNull();
    });
    expect(screen.queryByRole('button', { name: '이미지' })).toBeNull();
  });

  test('S5-AC-54 MarkdownEditor 이미지 단추 이름: imageLabel을 주면 그 이름이다', async () => {
    const { onImageUpload } = uploads();
    render(<Controlled onImageUpload={onImageUpload} imageLabel="Image" />);

    await tool('Image');
    expect(toolNames()).toEqual([...TOOLS, 'Image']);
  });

  test('S5-AC-54 MarkdownEditor 이미지 단추 키보드: 화살표·Home·End의 줄에 들고 Tab 자리는 여전히 하나다', async () => {
    const { onImageUpload } = uploads();
    render(<Controlled onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const bold = await tool('굵게');
    const image = await imageButton();
    const tabStops = () => within(screen.getByRole('toolbar')).getAllByRole('button').filter((b) => b.getAttribute('tabindex') === '0');

    expect(tabStops()).toEqual([bold]);
    bold.focus();
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(image);
    expect(tabStops()).toEqual([image]);
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(bold);
    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(image);
    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(await tool('링크'));
    await user.keyboard('{ArrowRight}{Home}');
    expect(document.activeElement).toBe(bold);
  });

  test('S5-AC-54 MarkdownEditor 이미지 단추 누름: 이어진 파일 입력(PNG, JPEG, GIF, WebP)을 연다', async () => {
    const { onImageUpload } = uploads();
    render(<Controlled onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const input = await imageInput();
    const opened = vi.fn();
    input.addEventListener('click', opened);

    expect((input.getAttribute('accept') ?? '').split(',').map((type) => type.trim()).sort()).toEqual(['image/gif', 'image/jpeg', 'image/png', 'image/webp']);
    await user.click(await imageButton());
    expect(opened, '단추를 누르면 파일 고르기가 열린다').toHaveBeenCalledTimes(1);
    expect(onImageUpload, '고르기 전에는 올리지 않는다').not.toHaveBeenCalled();
  });
});

describe('MarkdownEditor 이미지 올리기', () => {
  test('S5-AC-54 MarkdownEditor 파일 고르기: 고른 파일로 onImageUpload를 부르고, 올리는 중에는 role="status" 한 줄에 파일 이름과 onImageBusyChange(true), 편집칸은 계속 쓸 수 있다', async () => {
    const { onImageUpload, started } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const user = userEvent.setup();
    const shot = png('shot.png');

    await user.upload(await imageInput(), shot);
    const upload = await started(1);

    expect(onImageUpload).toHaveBeenCalledTimes(1);
    expect(onImageUpload.mock.calls[0]?.[0]).toBe(shot);
    await waitFor(() => {
      expect(statusLine()).toContain('이미지 올리는 중… (shot.png)');
    });
    expect(onImageBusyChange).toHaveBeenLastCalledWith(true);
    expect(await area()).toBeEnabled();
    expect(onChange, '올라가기 전에는 본문이 그대로다').not.toHaveBeenCalled();

    await upload.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(onImageBusyChange).toHaveBeenLastCalledWith(false);
    });
    expect(statusLine(), '올라가면 아래 줄이 사라진다').not.toContain('이미지 올리는 중');
  });

  test('S5-AC-54 MarkdownEditor 올라감: 올리기를 시작한 커서 자리에 ![파일 이름](attachment:{id})와 줄 바꿈이 든 값으로 onChange', async () => {
    const { onImageUpload, started } = uploads();
    const onChange = vi.fn();
    render(<Controlled initial="앞글뒷글" onChange={onChange} onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();

    cursor(t, 2);
    await user.upload(await imageInput(), png('shot.png'));
    await (await started(1)).answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(`앞글${syntax('shot.png', ID)}뒷글`);
    });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(`앞글![shot.png](attachment:${ID})\n뒷글`);
    expect(alertLine()).toBe('');
  });

  test('S5-AC-54 MarkdownEditor 올라감: 올리는 사이 앞쪽에 글을 더 썼으면 그만큼 민 자리에, 뒤쪽에 썼으면 그 자리에 넣는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="앞글뒷글" onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();

    cursor(t, 2);
    await user.upload(await imageInput(), png('shot.png'));
    const upload = await started(1);
    await user.type(t, '새 글. ', { initialSelectionStart: 0, initialSelectionEnd: 0 });
    await user.type(t, ' 끝', { initialSelectionStart: t.value.length, initialSelectionEnd: t.value.length });
    expect(t).toHaveValue('새 글. 앞글뒷글 끝');
    await upload.answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(`새 글. 앞글${syntax('shot.png', ID)}뒷글 끝`);
    });
  });

  test('S5-AC-54 MarkdownEditor 붙여 넣기: 글자만이면 가로채지 않고 지금처럼 붙고, 이미지 파일이 있으면 가로채 올린다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="" onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();
    await imageButton();

    t.focus();
    await user.paste('붙인 글\n');
    expect(t).toHaveValue('붙인 글\n');
    expect(onImageUpload, '글자만 붙이면 올리지 않는다').not.toHaveBeenCalled();

    const clip = png('clip.png');
    cursor(t, t.value.length);
    const notPrevented = fireEvent.paste(t, { clipboardData: transfer([clip]) });
    expect(notPrevented, '이미지 붙여 넣기는 가로챈다').toBe(false);
    const upload = await started(1);
    expect(onImageUpload.mock.calls[0]?.[0]).toBe(clip);

    await upload.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`붙인 글\n${syntax('clip.png', ID)}`);
    });
  });

  test('S5-AC-54 MarkdownEditor 끌어다 놓기: 끌어 오는 동안 "여기에 놓으면 이미지를 넣습니다", 놓으면 그 파일로 onImageUpload를 부른다', async () => {
    const { onImageUpload, started } = uploads();
    const { container } = render(<Controlled initial="본문" onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();
    const drag = png('drag.png');
    expect(text(container)).not.toContain(DROP_HERE);

    fireEvent.dragEnter(t, { dataTransfer: transfer([drag]) });
    const overNotPrevented = fireEvent.dragOver(t, { dataTransfer: transfer([drag]) });
    expect(overNotPrevented, '놓을 수 있게 dragover를 받는다').toBe(false);
    await waitFor(() => {
      expect(text(container)).toContain(DROP_HERE);
    });

    const dropNotPrevented = fireEvent.drop(t, { dataTransfer: transfer([drag]) });
    expect(dropNotPrevented, '놓은 파일을 브라우저가 열지 않게 한다').toBe(false);
    const upload = await started(1);
    expect(onImageUpload.mock.calls[0]?.[0]).toBe(drag);
    await waitFor(() => {
      expect(text(container), '놓으면 끌어 오는 중의 안내가 사라진다').not.toContain(DROP_HERE);
    });

    await upload.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t.value).toContain(`![drag.png](attachment:${ID})\n`);
    });
  });

  test('S5-AC-54 MarkdownEditor 끌어다 놓기: 끌다가 나가면 안내가 사라진다', async () => {
    const { onImageUpload } = uploads();
    const { container } = render(<Controlled onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    fireEvent.dragEnter(t, { dataTransfer: transfer([png('drag.png')]) });
    fireEvent.dragOver(t, { dataTransfer: transfer([png('drag.png')]) });
    await waitFor(() => {
      expect(text(container)).toContain(DROP_HERE);
    });
    fireEvent.dragLeave(t, { dataTransfer: transfer([png('drag.png')]) });

    await waitFor(() => {
      expect(text(container)).not.toContain(DROP_HERE);
    });
    expect(onImageUpload).not.toHaveBeenCalled();
  });

  test('S5-AC-54 MarkdownEditor 끌어다 놓기: 이미지가 아닌 파일은 올리지 않고 안내하며, 함께 놓은 이미지는 올린다', async () => {
    const { onImageUpload, names, started } = uploads();
    const onChange = vi.fn();
    const { container } = render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    fireEvent.drop(t, { dataTransfer: transfer([file('trace.log', 2048, 'text/plain')]) });
    await waitFor(() => {
      expect(text(container)).toContain(IMAGES_ONLY);
    });
    expect(onImageUpload, '이미지가 아닌 파일로는 부르지 않는다').not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.drop(t, { dataTransfer: transfer([file('notes.txt', 10, 'text/plain'), png('shot.png')]) });
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t.value).toContain(`![shot.png](attachment:${ID})\n`);
    });
    expect(names()).toEqual(['shot.png']);
  });

  test('S5-AC-55 MarkdownEditor 실패: onChange를 부르지 않고 role="alert"에 받은 글을 보인다. 다음 올리기를 시작하면 그 글이 사라진다', async () => {
    const { onImageUpload, started } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    render(<Controlled initial="쓰던 글" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const user = userEvent.setup();
    const t = await area();

    await user.upload(await imageInput(), png('notes.png'));
    await (await started(1)).answer({ ok: false, message: NOT_IMAGE });

    await waitFor(() => {
      expect(alertLine()).toContain(NOT_IMAGE);
    });
    expect(onChange).not.toHaveBeenCalled();
    expect(t).toHaveValue('쓰던 글');
    expect(onImageBusyChange).toHaveBeenLastCalledWith(false);
    expect(statusLine()).not.toContain('이미지 올리는 중');

    fireEvent.drop(t, { dataTransfer: transfer([png('again.png')]) });
    const second = await started(2);
    await waitFor(() => {
      expect(alertLine()).not.toContain(NOT_IMAGE);
    });
    await second.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t.value).toContain(`![again.png](attachment:${ID})\n`);
    });
  });

  test('S5-AC-54 MarkdownEditor 여러 파일: 차례로 하나씩 올리고(앞의 것이 끝나기 전에 다음을 부르지 않는다) 하나가 실패해도 나머지는 올린다', async () => {
    const { onImageUpload, names, started } = uploads();
    const onImageBusyChange = vi.fn();
    render(<Controlled initial="" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();
    await imageButton();

    fireEvent.drop(t, { dataTransfer: transfer([png('a.png'), png('b.png'), png('c.png')]) });
    const first = await started(1);
    await waitFor(() => {
      expect(statusLine()).toContain('a.png');
    });
    expect(names(), '첫째가 끝나기 전').toEqual(['a.png']);

    await first.answer({ ok: true, id: ID });
    const second = await started(2);
    expect(names(), '둘째가 끝나기 전').toEqual(['a.png', 'b.png']);
    expect(onImageBusyChange).toHaveBeenLastCalledWith(true);

    await second.answer({ ok: false, message: NOT_IMAGE });
    const third = await started(3);
    expect(names()).toEqual(['a.png', 'b.png', 'c.png']);

    await third.answer({ ok: true, id: ID_3 });
    await waitFor(() => {
      expect(t.value).toContain(syntax('c.png', ID_3));
    });
    expect(t.value).toContain(syntax('a.png', ID));
    expect(t.value).not.toContain('b.png');
    expect(t.value).not.toContain(ID_2);
    await waitFor(() => {
      expect(onImageBusyChange).toHaveBeenLastCalledWith(false);
    });
    expect(onImageUpload).toHaveBeenCalledTimes(3);
  });

  test('S5-AC-54 MarkdownEditor 막힘: 이미지 단추가 막히고 붙여 넣은 이미지와 놓은 파일을 받지 않는다', async () => {
    const { onImageUpload } = uploads();
    const onChange = vi.fn();
    render(<Controlled disabled initial="본문" onChange={onChange} onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();

    expect(await imageButton()).toBeDisabled();
    await user.click(await imageButton());
    fireEvent.paste(t, { clipboardData: transfer([png('clip.png')]) });
    fireEvent.drop(t, { dataTransfer: transfer([png('drag.png')]) });

    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(statusLine()).not.toContain('이미지 올리는 중');
  });

  test('S5-AC-54 MarkdownEditor 떠난 뒤 온 답: 편집기가 사라진 뒤 올라간 이미지는 버린다(onChange를 부르지 않는다)', async () => {
    const { onImageUpload, started } = uploads();
    const onChange = vi.fn();
    const { unmount } = render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} />);
    const user = userEvent.setup();

    await user.upload(await imageInput(), png('shot.png'));
    const upload = await started(1);
    unmount();
    await upload.answer({ ok: true, id: ID });

    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('MarkdownEditor 이미지 미리 보기', () => {
  const src = (id: string) => `/api/test/images/${id}`;

  test('S5-AC-56 MarkdownEditor 미리 보기: imageSrc로 이미지를 그리고, imageSrc가 없으면 글자다', async () => {
    const source = `![오류 화면](attachment:${ID})`;
    const { rerender } = render(<Controlled initial={source} mode="preview" />);
    const panel = await found(() => screen.queryByRole('tabpanel', { name: '미리 보기' }), '미리 보기 패널');
    expect(text(panel)).toContain('오류 화면');
    expect(panel.querySelectorAll('img')).toHaveLength(0);

    const imageSrc = vi.fn(src);
    rerender(<Controlled initial={source} mode="preview" imageSrc={imageSrc} />);
    const img = await found(() => within(panel).queryByRole('img', { name: '오류 화면' }), '미리 보기의 이미지');
    expect(img).toHaveAttribute('src', src(ID));
    expect(imageSrc).toHaveBeenCalledWith(ID);
  });

  test('S5-AC-56 MarkdownEditor 미리 보기: 방금 올려 넣은 이미지를 미리 보기 탭에서 그린다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="" onImageUpload={onImageUpload} imageSrc={src} />);
    const user = userEvent.setup();

    await user.upload(await imageInput(), png('shot.png'));
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(async () => {
      expect((await area()).value).toContain(ID);
    });
    await user.click(await found(() => screen.queryByRole('tab', { name: '미리 보기' }), '탭 "미리 보기"'));

    const panel = await found(() => screen.queryByRole('tabpanel', { name: '미리 보기' }), '미리 보기 패널');
    expect(await found(() => within(panel).queryByRole('img', { name: 'shot.png' }), '미리 보기의 이미지')).toHaveAttribute('src', src(ID));
  });
});

describe('MarkdownEditor 이미지 axe', () => {
  test('S5-AC-54 MarkdownEditor 이미지 axe: 이미지 단추, 올리는 중, 실패 줄에 접근성 위반이 없다', async () => {
    const failing = uploads();
    const busy = uploads();
    render(
      <div>
        <Controlled onImageUpload={failing.onImageUpload} />
        <MarkdownEditor label="댓글" minHeight={112} value="" onChange={() => undefined} onImageUpload={busy.onImageUpload} internal />
      </div>,
    );
    const user = userEvent.setup();
    const comment = await found(() => screen.queryByRole('textbox', { name: '댓글' }), '편집칸 "댓글"');

    await user.upload(await imageInput(), png('notes.png'));
    await (await failing.started(1)).answer({ ok: false, message: NOT_IMAGE });
    await waitFor(() => {
      expect(alertLine()).toContain(NOT_IMAGE);
    });
    fireEvent.drop(comment, { dataTransfer: transfer([png('shot.png')]) });
    await busy.started(1);
    await waitFor(() => {
      expect(statusLine()).toContain('이미지 올리는 중… (shot.png)');
    });

    await expectNoAxeViolations();
  });
});
