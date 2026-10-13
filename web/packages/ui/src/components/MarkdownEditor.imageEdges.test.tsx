// 5번 묶음 screens.md 1절과 5절, 편집기 prop의 설명이 정한 것 가운데 MarkdownEditor.images.test.tsx가
// 값까지 고정하지 않은 가장자리: 올리는 사이 글이 바뀔 때의 넣는 자리, 넣은 뒤의 커서, 실패 줄과 안내가
// 사라지는 때(imageNoticesKey), 던지는 올리기 함수, 막힘, 붙여 넣기와 끌어다 놓기의 갈림, 떠날 때.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { file, found, must, text } from '../__tests__/support';
import { MarkdownEditor, type MarkdownEditorProps } from './MarkdownEditor';

const ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const ID_2 = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e02';
const NOT_IMAGE = 'PNG, JPEG, GIF, WebP 이미지만 본문에 넣을 수 있습니다';
const TOO_LARGE = '파일 하나는 10MB까지입니다.';
const DROP_HERE = '여기에 놓으면 이미지를 넣습니다';
const IMAGES_ONLY = '이미지 파일만 본문에 넣을 수 있습니다. 다른 파일은 첨부로 올리세요';

type UploadResult = { ok: true; id: string } | { ok: false; message: string };

const png = (name: string) => file(name, 1024, 'image/png');
const log = (name: string) => file(name, 2048, 'text/plain');
const syntax = (name: string, id: string) => `![${name}](attachment:${id})\n`;
/** What goes in for shot.png. */
const SHOT = syntax('shot.png', ID);

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

/** Lets what is already on its way (promise answers, React's drawing) finish, so "no call" is said after it. */
async function settled() {
  await act(async () => {
    for (let turn = 0; turn < 10; turn += 1) await Promise.resolve();
  });
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
const imageButton = () => found(() => screen.queryByRole('button', { name: '이미지' }), '툴바 버튼 "이미지"');
const statusLine = () => screen.queryAllByRole('status').map((el) => text(el)).join(' ');
const alertLine = () => screen.queryAllByRole('alert').map((el) => text(el)).join(' ');
const caret = (t: HTMLTextAreaElement) => [t.selectionStart, t.selectionEnd];

/** The file input the "이미지" button opens: the nearest image file input around the button. */
function imageInputOf(button: HTMLElement): HTMLInputElement | null {
  for (let el = button.parentElement; el; el = el.parentElement) {
    const input = el.querySelector<HTMLInputElement>('input[type="file"]');
    if (input) return input;
  }
  return null;
}

async function imageInput() {
  return must(imageInputOf(await imageButton()), '"이미지" 단추와 이어진 파일 입력');
}

/** What a paste or a drop of files carries. jsdom has no DataTransfer, so this holds the parts a handler reads. */
function transfer(files: File[]) {
  return {
    files: Object.assign([...files], { item: (index: number) => files[index] ?? null }),
    items: files.map((f) => ({ kind: 'file', type: f.type, getAsFile: () => f })),
    types: files.length > 0 ? ['Files'] : [],
    getData: () => '',
  };
}

/** What a drag carries before the drop: the files are not readable yet, only `types` says they come. */
function dragging(files: File[]) {
  return {
    files: Object.assign([] as File[], { item: () => null }),
    items: files.map((f) => ({ kind: 'file', type: f.type, getAsFile: () => null })),
    types: ['Files'],
    getData: () => '',
  };
}

/** A piece of text dragged or pasted: no files. */
function textOnly(value: string) {
  return {
    files: Object.assign([] as File[], { item: () => null }),
    items: [{ kind: 'string', type: 'text/plain', getAsFile: () => null }],
    types: ['text/plain'],
    getData: () => value,
  };
}

/** Puts the cursor (or a selection) in the text area. */
function select(t: HTMLTextAreaElement, start: number, end = start) {
  t.focus();
  t.setSelectionRange(start, end);
}

/** Starts an upload of `files` with the cursor at `at` (a selection when `end` is given). */
function dropAt(t: HTMLTextAreaElement, files: File[], at: number, end = at) {
  select(t, at, end);
  return fireEvent.drop(t, { dataTransfer: transfer(files) });
}

/** Presses `keys` with the cursor at `start` (over the selection `start`..`end`). */
async function typeAt(user: UserEvent, t: HTMLTextAreaElement, keys: string, start: number, end = start) {
  await user.type(t, keys, { initialSelectionStart: start, initialSelectionEnd: end });
}

/** Replaces `start`..`end` with `value` in one change, as a paste does. */
async function pasteOver(user: UserEvent, t: HTMLTextAreaElement, value: string, start: number, end = start) {
  select(t, start, end);
  await user.paste(value);
}

interface Moved {
  name: string;
  initial: string;
  /** Where the cursor is when the upload begins. */
  at: number;
  /** What the writer does while the file is going up. */
  edit: (user: UserEvent, t: HTMLTextAreaElement) => Promise<void>;
  /** The text after the edit, before the answer. */
  edited: string;
  /** The text after the image went in. */
  result: string;
}

const MOVED: Moved[] = [
  {
    name: '자리 앞에 쓰면 쓴 만큼 민 자리에',
    initial: 'abcdef',
    at: 4,
    edit: (user, t) => typeAt(user, t, 'XY', 0),
    edited: 'XYabcdef',
    result: `XYabcd${SHOT}ef`,
  },
  {
    name: '자리 한 글자 앞에 써도 민다',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => typeAt(user, t, 'X', 1),
    edited: 'aXbcdef',
    result: `aXb${SHOT}cdef`,
  },
  {
    name: '자리 뒤에 쓰면 그 자리에',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => typeAt(user, t, 'XY', 6),
    edited: 'abcdefXY',
    result: `ab${SHOT}cdefXY`,
  },
  {
    name: '자리 한 글자 뒤에 써도 그 자리에',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => typeAt(user, t, 'X', 3),
    edited: 'abcXdef',
    result: `ab${SHOT}cXdef`,
  },
  {
    name: '바로 그 자리에 쓰면 올리기를 시작한 자리(쓴 글의 앞)에',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => typeAt(user, t, 'XY', 2),
    edited: 'abXYcdef',
    result: `ab${SHOT}XYcdef`,
  },
  {
    name: '자리를 걸친 글을 다른 글로 바꾸면 바꾼 글의 끝에',
    initial: 'abcdef',
    at: 3,
    edit: (user, t) => pasteOver(user, t, 'XYZ', 1, 5),
    edited: 'aXYZf',
    result: `aXYZ${SHOT}f`,
  },
  {
    name: '자리에서 시작하는 글을 바꾸면 그 자리(바꾼 글의 앞)에',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => pasteOver(user, t, 'XY', 2, 3),
    edited: 'abXYdef',
    result: `ab${SHOT}XYdef`,
  },
  {
    name: '자리에서 끝나는 글을 바꾸면 바꾼 글의 뒤에',
    initial: 'abcdef',
    at: 3,
    edit: (user, t) => pasteOver(user, t, 'XY', 2, 3),
    edited: 'abXYdef',
    result: `abXY${SHOT}def`,
  },
  {
    name: '자리 앞의 글자를 지우면 지운 만큼 당긴 자리에',
    initial: 'abcdef',
    at: 4,
    edit: (user, t) => typeAt(user, t, '{Backspace}', 2),
    edited: 'acdef',
    result: `acd${SHOT}ef`,
  },
  {
    name: '자리 뒤의 글자를 지우면 그 자리에',
    initial: 'abcdef',
    at: 2,
    edit: (user, t) => typeAt(user, t, '{Delete}', 4),
    edited: 'abcdf',
    result: `ab${SHOT}cdf`,
  },
  {
    name: '자리를 걸친 글을 지우면 지운 곳에',
    initial: 'abcdef',
    at: 3,
    edit: (user, t) => typeAt(user, t, '{Delete}', 1, 5),
    edited: 'af',
    result: `a${SHOT}f`,
  },
  {
    name: '글을 다 지우면 빈 글에 이미지만',
    initial: 'abcdef',
    at: 3,
    edit: (user, t) => user.clear(t),
    edited: '',
    result: SHOT,
  },
  {
    name: '커서가 맨 앞이고 끝에 쓰면 맨 앞에',
    initial: 'abcdef',
    at: 0,
    edit: (user, t) => typeAt(user, t, 'XY', 6),
    edited: 'abcdefXY',
    result: `${SHOT}abcdefXY`,
  },
  {
    name: '커서가 맨 앞이고 맨 앞에 쓰면 쓴 글의 앞에',
    initial: 'abcdef',
    at: 0,
    edit: (user, t) => typeAt(user, t, 'XY', 0),
    edited: 'XYabcdef',
    result: `${SHOT}XYabcdef`,
  },
  {
    name: '커서가 맨 끝이고 앞에 쓰면 민 맨 끝에',
    initial: 'abcdef',
    at: 6,
    edit: (user, t) => typeAt(user, t, 'XY', 0),
    edited: 'XYabcdef',
    result: `XYabcdef${SHOT}`,
  },
  {
    name: '커서가 맨 끝이고 이어서 쓰면 올리기를 시작한 자리(이어 쓴 글의 앞)에',
    initial: 'abcdef',
    at: 6,
    edit: (user, t) => typeAt(user, t, 'XY', 6),
    edited: 'abcdefXY',
    result: `abcdef${SHOT}XY`,
  },
  {
    name: '빈 글에서 시작해 글을 쓰면 쓴 글의 앞에',
    initial: '',
    at: 0,
    edit: (user, t) => typeAt(user, t, 'XY', 0),
    edited: 'XY',
    result: `${SHOT}XY`,
  },
  {
    name: '같은 글자뿐인 글(aaaa)의 끝이 자리이고 끝에 a를 쓰면 쓴 a의 앞에',
    initial: 'aaaa',
    at: 4,
    edit: (user, t) => typeAt(user, t, 'a', 4),
    edited: 'aaaaa',
    result: `aaaa${SHOT}a`,
  },
  {
    name: '같은 글자뿐인 글(aaaa)의 가운데가 자리이고 끝에 a를 쓰면 그 자리에',
    initial: 'aaaa',
    at: 2,
    edit: (user, t) => typeAt(user, t, 'a', 4),
    edited: 'aaaaa',
    result: `aa${SHOT}aaa`,
  },
  {
    name: '같은 글자뿐인 글(aaaa)에서 자리를 걸친 뒤 두 글자를 지우면 남은 글의 끝에',
    initial: 'aaaa',
    at: 3,
    edit: (user, t) => typeAt(user, t, '{Delete}', 2, 4),
    edited: 'aa',
    result: `aa${SHOT}`,
  },
  {
    name: '되풀이되는 글(abab)에서 자리를 걸친 뒤 두 글자를 지우면 남은 글의 끝에',
    initial: 'abab',
    at: 3,
    edit: (user, t) => typeAt(user, t, '{Delete}', 2, 4),
    edited: 'ab',
    result: `ab${SHOT}`,
  },
  {
    name: '같은 글자가 이어진 글(aaaab)에서 자리 바로 앞의 a를 지우면 한 글자 당긴 자리에',
    initial: 'aaaab',
    at: 4,
    edit: (user, t) => typeAt(user, t, '{Backspace}', 4),
    edited: 'aaab',
    result: `aaa${SHOT}b`,
  },
];

describe('MarkdownEditor 이미지 자리', () => {
  test.each(MOVED)('S5-AC-54 MarkdownEditor 이미지 자리, 올리는 사이 바뀐 글: $name 넣는다', async ({ initial, at, edit, edited, result }) => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial={initial} onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();

    dropAt(t, [png('shot.png')], at);
    const upload = await started(1);
    await edit(user, t);
    expect(t, '올라가기 전의 글').toHaveValue(edited);
    await upload.answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(result);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리: 글을 고른 채 올리면 고른 글은 그대로 두고 고른 곳의 끝에 넣는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 1, 4);
    await (await started(1)).answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(`abcd${SHOT}ef`);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리, 여러 파일: 글 가운데에 고른 차례대로 이어서 넣는다(첫 파일이 위)', async () => {
    const { onImageUpload, started } = uploads();
    const onChange = vi.fn();
    render(<Controlled initial="앞글뒷글" onChange={onChange} onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('a.png'), png('b.png')], 2);
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`앞글${syntax('a.png', ID)}뒷글`);
    });
    await (await started(2)).answer({ ok: true, id: ID_2 });

    await waitFor(() => {
      expect(t).toHaveValue(`앞글${syntax('a.png', ID)}${syntax('b.png', ID_2)}뒷글`);
    });
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리, 여러 파일: 첫 파일이 들어간 뒤 앞과 뒤에 글을 써도 둘째는 첫째 바로 아래에 넣는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="앞글뒷글" onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();
    const first = syntax('a.png', ID);

    dropAt(t, [png('a.png'), png('b.png')], 2);
    await (await started(1)).answer({ ok: true, id: ID });
    const second = await started(2);
    await waitFor(() => {
      expect(t).toHaveValue(`앞글${first}뒷글`);
    });
    await typeAt(user, t, 'XY', 0);
    await typeAt(user, t, 'Z', t.value.length);
    expect(t).toHaveValue(`XY앞글${first}뒷글Z`);
    await second.answer({ ok: true, id: ID_2 });

    await waitFor(() => {
      expect(t).toHaveValue(`XY앞글${first}${syntax('b.png', ID_2)}뒷글Z`);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리, 올리는 중에 또 올림: 뒤쪽 자리에서 시작한 둘째는 첫째가 들어가고 그사이 글을 써도 제 자리에 넣는다', async () => {
    const { onImageUpload, names, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();

    dropAt(t, [png('a.png')], 2);
    const first = await started(1);
    dropAt(t, [png('b.png')], 4);
    await typeAt(user, t, 'XY', 0);
    expect(t).toHaveValue('XYabcdef');
    expect(names(), '첫째가 끝나기 전에는 둘째를 올리지 않는다').toEqual(['a.png']);

    await first.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`XYab${syntax('a.png', ID)}cdef`);
    });
    await (await started(2)).answer({ ok: true, id: ID_2 });

    await waitFor(() => {
      expect(t).toHaveValue(`XYab${syntax('a.png', ID)}cd${syntax('b.png', ID_2)}ef`);
    });
    expect(names()).toEqual(['a.png', 'b.png']);
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리, 올리는 중에 또 올림: 앞쪽 자리에서 시작한 둘째는 첫째가 들어가도 그 자리에 넣는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('a.png')], 4);
    const first = await started(1);
    dropAt(t, [png('b.png')], 2);
    await first.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`abcd${syntax('a.png', ID)}ef`);
    });
    await (await started(2)).answer({ ok: true, id: ID_2 });

    await waitFor(() => {
      expect(t).toHaveValue(`ab${syntax('b.png', ID_2)}cd${syntax('a.png', ID)}ef`);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 자리, 올리는 중에 또 올림: 같은 자리에서 시작한 둘째는 먼저 시작한 첫째의 아래에 넣는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('a.png')], 2);
    const first = await started(1);
    dropAt(t, [png('b.png')], 2);
    await first.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`ab${syntax('a.png', ID)}cdef`);
    });
    await (await started(2)).answer({ ok: true, id: ID_2 });

    await waitFor(() => {
      expect(t).toHaveValue(`ab${syntax('a.png', ID)}${syntax('b.png', ID_2)}cdef`);
    });
  });
});

describe('MarkdownEditor 이미지를 넣은 뒤의 커서', () => {
  test('S5-AC-54 MarkdownEditor 이미지 커서: 쓰던 자리보다 앞에 들어가면 커서는 넣은 길이만큼 밀려 쓰던 글자 사이에 남는다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 2);
    const upload = await started(1);
    select(t, 5);
    await upload.answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(caret(t)).toEqual([5 + SHOT.length, 5 + SHOT.length]);
    });
    expect(t).toHaveValue(`ab${SHOT}cdef`);
  });

  test('S5-AC-54 MarkdownEditor 이미지 커서: 쓰던 자리보다 뒤에 들어가면 커서는 그대로다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 4);
    const upload = await started(1);
    select(t, 1);
    await upload.answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(`abcd${SHOT}ef`);
      expect(caret(t)).toEqual([1, 1]);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 커서: 커서가 넣는 자리에 그대로 있으면 넣은 글 뒤로 옮긴다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 2);
    await (await started(1)).answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(caret(t)).toEqual([2 + SHOT.length, 2 + SHOT.length]);
    });
    expect(t).toHaveValue(`ab${SHOT}cdef`);
  });

  test('S5-AC-54 MarkdownEditor 이미지 커서: 고른 글 사이에 들어가면 고른 곳의 앞은 그대로, 끝은 넣은 길이만큼 밀린다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="abcdef" onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 3);
    const upload = await started(1);
    select(t, 1, 5);
    await upload.answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(caret(t)).toEqual([1, 5 + SHOT.length]);
    });
    expect(t).toHaveValue(`abc${SHOT}def`);
  });
});

describe('MarkdownEditor 이미지 실패 줄과 안내', () => {
  /** A failed upload and a dropped file that is not an image: both lines are under the text area. */
  async function withBothLines(extra: Extra) {
    const { onImageUpload, started } = uploads();
    const view = render(<Controlled initial="쓰던 글" onImageUpload={onImageUpload} {...extra} />);
    const t = await area();

    dropAt(t, [png('notes.png')], 0);
    await (await started(1)).answer({ ok: false, message: NOT_IMAGE });
    await waitFor(() => {
      expect(alertLine()).toContain(NOT_IMAGE);
    });
    fireEvent.drop(t, { dataTransfer: transfer([log('trace.log')]) });
    await waitFor(() => {
      expect(alertLine()).toContain(IMAGES_ONLY);
    });
    expect(alertLine(), '이미지가 아닌 파일을 놓은 것은 올리기가 아니라서 실패 줄이 남는다').toContain(`notes.png: ${NOT_IMAGE}`);
    return { ...view, onImageUpload, t };
  }

  test('S5-AC-55 MarkdownEditor 이미지 imageNoticesKey: 값이 바뀌면 실패 줄과 "이미지 파일만" 안내가 사라지고 글은 그대로다', async () => {
    const { rerender, onImageUpload, t } = await withBothLines({ imageNoticesKey: 1 });

    rerender(<Controlled initial="쓰던 글" onImageUpload={onImageUpload} imageNoticesKey={2} />);

    await waitFor(() => {
      expect(screen.queryAllByRole('alert')).toEqual([]);
    });
    expect(t).toHaveValue('쓰던 글');
  });

  test('S5-AC-55 MarkdownEditor 이미지 imageNoticesKey: 값이 같은 채 다시 그리면 실패 줄과 안내가 남는다', async () => {
    const { rerender, onImageUpload } = await withBothLines({ imageNoticesKey: 'saved-1' });

    rerender(<Controlled initial="쓰던 글" onImageUpload={onImageUpload} imageNoticesKey="saved-1" helpText="도움말" />);
    await found(() => screen.queryByText('도움말'), '다시 그린 도움말');
    await settled();

    expect(alertLine()).toContain(`notes.png: ${NOT_IMAGE}`);
    expect(alertLine()).toContain(IMAGES_ONLY);
  });

  test('S5-AC-55 MarkdownEditor 이미지 imageNoticesKey: 없던 값이 생겨도 새 값이라 실패 줄과 안내가 사라진다', async () => {
    const { rerender, onImageUpload } = await withBothLines({});

    rerender(<Controlled initial="쓰던 글" onImageUpload={onImageUpload} helpText="도움말" />);
    await found(() => screen.queryByText('도움말'), '다시 그린 도움말');
    await settled();
    expect(alertLine(), '값이 없는 채 다시 그리면 남는다').toContain(`notes.png: ${NOT_IMAGE}`);

    rerender(<Controlled initial="쓰던 글" onImageUpload={onImageUpload} helpText="도움말" imageNoticesKey={0} />);
    await waitFor(() => {
      expect(screen.queryAllByRole('alert')).toEqual([]);
    });
  });

  test('S5-AC-55 MarkdownEditor 이미지 실패, 여러 파일: 실패 줄마다 파일 이름과 까닭이 있고 같은 묶음의 다음 파일을 올리는 동안에도 남는다', async () => {
    const { onImageUpload, started } = uploads();
    const onChange = vi.fn();
    render(<Controlled initial="쓰던 글" onChange={onChange} onImageUpload={onImageUpload} />);
    const t = await area();

    dropAt(t, [png('a.png'), png('b.png'), png('c.png')], 0);
    await (await started(1)).answer({ ok: false, message: NOT_IMAGE });
    const second = await started(2);
    await waitFor(() => {
      expect(statusLine()).toContain('이미지 올리는 중… (b.png)');
    });
    expect(alertLine(), '둘째를 올리는 동안').toBe(`a.png: ${NOT_IMAGE}`);

    await second.answer({ ok: true, id: ID });
    await (await started(3)).answer({ ok: false, message: TOO_LARGE });

    await waitFor(() => {
      expect(alertLine()).toContain(`c.png: ${TOO_LARGE}`);
    });
    expect(alertLine()).toContain(`a.png: ${NOT_IMAGE}`);
    expect(alertLine()).not.toContain('b.png');
    expect(t).toHaveValue(`${syntax('b.png', ID)}쓰던 글`);
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  test('S5-AC-55 MarkdownEditor 이미지 실패, 던지는 올리기 함수: 실패 줄에 파일 이름이 나오고 뒤의 파일은 올라가며 올리는 중이 끝난다', async () => {
    const onImageUpload = vi.fn<(file: File) => Promise<UploadResult>>((f) =>
      f.name === 'boom.png' ? Promise.reject(new Error('연결이 끊겼다')) : Promise.resolve({ ok: true, id: ID_2 }),
    );
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    render(<Controlled initial="앞글뒷글" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    dropAt(t, [png('boom.png'), png('ok.png')], 2);

    await waitFor(() => {
      expect(t).toHaveValue(`앞글${syntax('ok.png', ID_2)}뒷글`);
    });
    await waitFor(() => {
      expect(onImageBusyChange).toHaveBeenLastCalledWith(false);
    });
    expect(onImageBusyChange.mock.calls).toEqual([[true], [false]]);
    expect(alertLine(), '까닭을 받지 못했으니 파일 이름만').toBe('boom.png');
    expect(statusLine()).not.toContain('이미지 올리는 중');
    expect(onImageUpload.mock.calls.map(([f]) => f.name)).toEqual(['boom.png', 'ok.png']);
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  test('S5-AC-55 MarkdownEditor 이미지 실패, 던지는 올리기 함수: 하나뿐인 파일이 던져도 올리는 중이 끝나고 다음 올리기를 받는다', async () => {
    // The first call throws, the next ones go up.
    const onImageUpload = vi.fn<(file: File) => Promise<UploadResult>>(async (f) => {
      await Promise.resolve();
      if (f.name === 'boom.png') throw new Error('연결이 끊겼다');
      return { ok: true, id: ID };
    });
    const onImageBusyChange = vi.fn();
    render(<Controlled initial="글" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    dropAt(t, [png('boom.png')], 1);
    await waitFor(() => {
      expect(alertLine()).toBe('boom.png');
    });
    await waitFor(() => {
      expect(onImageBusyChange.mock.calls).toEqual([[true], [false]]);
    });
    expect(t).toHaveValue('글');

    dropAt(t, [png('shot.png')], 1);
    await waitFor(() => {
      expect(t).toHaveValue(`글${SHOT}`);
    });
    expect(screen.queryAllByRole('alert'), '다음 올리기를 시작하면 실패 줄이 사라진다').toEqual([]);
    await waitFor(() => {
      expect(onImageBusyChange.mock.calls).toEqual([[true], [false], [true], [false]]);
    });
  });

  test('S5-AC-55 MarkdownEditor 이미지 실패, 던지는 올리기 함수: Promise를 주기 전에 던져도 실패 줄이 나오고 올리는 중이 끝나며 다음 올리기를 받는다', async () => {
    const onImageUpload = vi.fn<(file: File) => Promise<UploadResult>>((f) => {
      if (f.name === 'boom.png') throw new Error('연결이 끊겼다');
      return Promise.resolve({ ok: true, id: ID });
    });
    const onImageBusyChange = vi.fn();
    render(<Controlled initial="글" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    dropAt(t, [png('boom.png')], 1);
    await waitFor(() => {
      expect(alertLine()).toBe('boom.png');
    });
    await waitFor(() => {
      expect(onImageBusyChange.mock.calls).toEqual([[true], [false]]);
    });
    expect(t).toHaveValue('글');

    dropAt(t, [png('shot.png')], 1);
    await waitFor(() => {
      expect(t).toHaveValue(`글${SHOT}`);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 안내 글: imageUploadingText, imageDropText, imageOnlyText를 주면 그 글이다', async () => {
    const { onImageUpload, started } = uploads();
    const { container } = render(
      <Controlled
        onImageUpload={onImageUpload}
        imageUploadingText={(name) => `Uploading ${name}`}
        imageDropText="Drop to insert"
        imageOnlyText="Images only"
      />,
    );
    const t = await area();

    fireEvent.dragOver(t, { dataTransfer: dragging([png('shot.png')]) });
    await waitFor(() => {
      expect(text(container)).toContain('Drop to insert');
    });
    expect(text(container)).not.toContain(DROP_HERE);

    fireEvent.drop(t, { dataTransfer: transfer([png('shot.png'), log('trace.log')]) });
    await started(1);
    await waitFor(() => {
      expect(statusLine()).toBe('Uploading shot.png');
    });
    expect(alertLine()).toBe('Images only');
  });
});

describe('MarkdownEditor 이미지 붙여 넣기와 끌어다 놓기의 갈림', () => {
  test('S5-AC-54 MarkdownEditor 이미지 붙여 넣기: 글자만이거나 이미지가 아닌 파일이면 가로채지 않고 올리지도 알리지도 않는다', async () => {
    const { onImageUpload } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();
    await imageButton();

    expect(fireEvent.paste(t, { clipboardData: textOnly('붙인 글') }), '글자만 든 붙여 넣기는 브라우저에 맡긴다').toBe(true);
    expect(fireEvent.paste(t, { clipboardData: transfer([log('trace.log')]) }), '이미지가 아닌 파일의 붙여 넣기도 맡긴다').toBe(true);
    await settled();

    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onImageBusyChange).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryAllByRole('alert')).toEqual([]);
    expect(statusLine()).toBe('');
  });

  test('S5-AC-54 MarkdownEditor 이미지 붙여 넣기: 이미지와 다른 파일이 함께 있으면 가로채 이미지만 올린다', async () => {
    const { onImageUpload, names, started } = uploads();
    render(<Controlled initial="본문" onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    select(t, 2);
    const notPrevented = fireEvent.paste(t, { clipboardData: transfer([log('trace.log'), png('clip.png'), log('notes.txt')]) });
    expect(notPrevented, '이미지가 든 붙여 넣기는 가로챈다').toBe(false);
    await (await started(1)).answer({ ok: true, id: ID });

    await waitFor(() => {
      expect(t).toHaveValue(`본문${syntax('clip.png', ID)}`);
    });
    await settled();
    expect(names()).toEqual(['clip.png']);
    expect(screen.queryAllByRole('alert'), '"이미지 파일만" 안내는 놓은 파일의 것이다').toEqual([]);
  });

  test('S5-AC-54 MarkdownEditor 이미지 끌어다 놓기: 이미지와 다른 파일을 함께 놓으면 이미지는 올리고 "이미지 파일만" 안내도 보인다', async () => {
    const { onImageUpload, names, started } = uploads();
    render(<Controlled initial="본문" onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    const notPrevented = dropAt(t, [png('shot.png'), log('trace.log')], 0);
    expect(notPrevented, '놓은 파일을 브라우저가 열지 않게 한다').toBe(false);
    const upload = await started(1);
    await waitFor(() => {
      expect(alertLine()).toBe(IMAGES_ONLY);
    });

    await upload.answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`${SHOT}본문`);
    });
    await settled();
    expect(names()).toEqual(['shot.png']);
    expect(alertLine(), '이미지가 올라간 뒤에도 안내는 남는다').toBe(IMAGES_ONLY);
  });

  test('S5-AC-54 MarkdownEditor 이미지 끌어다 놓기: 이미지만 놓으면 안내가 없고, 앞서 보인 "이미지 파일만" 안내도 사라진다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled initial="본문" onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    dropAt(t, [png('a.png'), png('b.png')], 0);
    await started(1);
    await settled();
    expect(screen.queryAllByRole('alert'), '이미지만 놓았다').toEqual([]);
    await (await started(1)).answer({ ok: true, id: ID });
    await (await started(2)).answer({ ok: true, id: ID_2 });
    await waitFor(() => {
      expect(t).toHaveValue(`${syntax('a.png', ID)}${syntax('b.png', ID_2)}본문`);
    });

    fireEvent.drop(t, { dataTransfer: transfer([log('trace.log')]) });
    await waitFor(() => {
      expect(alertLine()).toBe(IMAGES_ONLY);
    });
    fireEvent.drop(t, { dataTransfer: transfer([png('c.png')]) });
    await started(3);
    await waitFor(() => {
      expect(screen.queryAllByRole('alert')).toEqual([]);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 끌어다 놓기: 이미지가 아닌 파일만 놓거나 파일 고르기를 그만두면 올리기가 시작되지 않는다(onImageBusyChange를 부르지 않는다)', async () => {
    const { onImageUpload } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    fireEvent.change(await imageInput(), { target: { files: [] } });
    const notPrevented = fireEvent.drop(t, { dataTransfer: transfer([log('trace.log')]) });
    expect(notPrevented, '놓은 파일을 브라우저가 열지 않게 한다').toBe(false);
    await waitFor(() => {
      expect(alertLine()).toBe(IMAGES_ONLY);
    });
    await settled();

    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onImageBusyChange).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(statusLine()).toBe('');
  });

  test('S5-AC-54 MarkdownEditor 이미지 끌어다 놓기: 파일이 아닌 글을 끌어 오면 안내가 없고 가로채지 않는다', async () => {
    const { onImageUpload } = uploads();
    const onChange = vi.fn();
    const { container } = render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    expect(fireEvent.dragEnter(t, { dataTransfer: textOnly('끌어 온 글') }), 'dragenter').toBe(true);
    expect(fireEvent.dragOver(t, { dataTransfer: textOnly('끌어 온 글') }), 'dragover').toBe(true);
    await settled();
    expect(text(container)).not.toContain(DROP_HERE);

    expect(fireEvent.drop(t, { dataTransfer: textOnly('끌어 온 글') }), 'drop').toBe(true);
    await settled();
    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryAllByRole('alert')).toEqual([]);
  });

  test('S5-AC-54 MarkdownEditor 이미지 끌어다 놓기: 편집칸에 들어오기만 해도(dragenter) 가로채고 안내를 보인다', async () => {
    const { onImageUpload } = uploads();
    const { container } = render(<Controlled onImageUpload={onImageUpload} />);
    const t = await area();
    await imageButton();

    expect(fireEvent.dragEnter(t, { dataTransfer: dragging([png('drag.png')]) })).toBe(false);

    await waitFor(() => {
      expect(text(container)).toContain(DROP_HERE);
    });
  });
});

describe('MarkdownEditor 이미지 막힘과 올리기 함수 없음', () => {
  test('S5-AC-54 MarkdownEditor 이미지 막힘: 붙여 넣기, 끌어 오기, 놓기를 가로채지 않고 올리지도 알리지도 않는다', async () => {
    const { onImageUpload } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    const { container } = render(
      <Controlled disabled initial="본문" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />,
    );
    const t = await area();
    await imageButton();
    const files = [png('shot.png'), log('trace.log')];

    expect(fireEvent.paste(t, { clipboardData: transfer(files) }), 'paste').toBe(true);
    expect(fireEvent.dragEnter(t, { dataTransfer: dragging(files) }), 'dragenter').toBe(true);
    expect(fireEvent.dragOver(t, { dataTransfer: dragging(files) }), 'dragover').toBe(true);
    await settled();
    expect(text(container)).not.toContain(DROP_HERE);
    expect(fireEvent.drop(t, { dataTransfer: transfer(files) }), 'drop').toBe(true);
    await settled();

    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onImageBusyChange).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryAllByRole('alert')).toEqual([]);
    expect(statusLine()).toBe('');
  });

  test('S5-AC-54 MarkdownEditor 이미지 막힘: 단추를 눌러도 파일 고르기가 열리지 않고, 파일 입력도 막혀 있으며 파일이 들어와도 올리지 않는다', async () => {
    const { onImageUpload } = uploads();
    const onImageBusyChange = vi.fn();
    render(<Controlled disabled initial="본문" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const user = userEvent.setup();
    const input = await imageInput();
    const opened = vi.fn();
    input.addEventListener('click', opened);

    await user.click(await imageButton());
    expect(opened).not.toHaveBeenCalled();
    expect(input).toBeDisabled();

    fireEvent.change(input, { target: { files: [png('shot.png')] } });
    await settled();
    expect(onImageUpload).not.toHaveBeenCalled();
    expect(onImageBusyChange).not.toHaveBeenCalled();
    expect(statusLine()).toBe('');
  });

  test('S5-AC-54 MarkdownEditor 이미지, onImageUpload 없음: 붙여 넣은 이미지와 끌어 온 파일을 가로채지 않고 안내도 없다', async () => {
    const onChange = vi.fn();
    const { container } = render(<Controlled initial="본문" onChange={onChange} />);
    const t = await area();
    const files = [png('shot.png'), log('trace.log')];

    expect(fireEvent.paste(t, { clipboardData: transfer(files) }), 'paste').toBe(true);
    expect(fireEvent.dragEnter(t, { dataTransfer: dragging(files) }), 'dragenter').toBe(true);
    expect(fireEvent.dragOver(t, { dataTransfer: dragging(files) }), 'dragover').toBe(true);
    await settled();
    expect(text(container)).not.toContain(DROP_HERE);
    expect(fireEvent.drop(t, { dataTransfer: transfer(files) }), 'drop').toBe(true);
    await settled();

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryAllByRole('alert')).toEqual([]);
    expect(statusLine()).toBe('');
  });
});

describe('MarkdownEditor 이미지와 떠남', () => {
  test('S5-AC-54 MarkdownEditor 이미지, 올리는 중에 떠남: onImageBusyChange(false)를 한 번 부르고, 뒤에 온 답은 넣지 않으며 남은 파일도 올리지 않는다', async () => {
    const { onImageUpload, names, started } = uploads();
    const onImageBusyChange = vi.fn();
    const onChange = vi.fn();
    const { unmount } = render(<Controlled initial="본문" onChange={onChange} onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    dropAt(t, [png('a.png'), png('b.png')], 0);
    const upload = await started(1);
    expect(onImageBusyChange.mock.calls).toEqual([[true]]);

    unmount();
    expect(onImageBusyChange.mock.calls, '떠날 때').toEqual([[true], [false]]);

    await upload.answer({ ok: true, id: ID });
    await settled();
    expect(onImageBusyChange.mock.calls, '답이 온 뒤').toEqual([[true], [false]]);
    expect(onChange).not.toHaveBeenCalled();
    expect(names()).toEqual(['a.png']);
  });

  test('S5-AC-54 MarkdownEditor 이미지, 올리는 것이 없을 때 떠남: onImageBusyChange를 부르지 않는다', async () => {
    const { onImageUpload } = uploads();
    const onImageBusyChange = vi.fn();
    const { unmount } = render(<Controlled initial="본문" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    await area();

    unmount();
    await settled();

    expect(onImageBusyChange).not.toHaveBeenCalled();
  });

  test('S5-AC-54 MarkdownEditor 이미지, 다 올라간 뒤에 떠남: onImageBusyChange는 true와 false 한 번씩뿐이다', async () => {
    const { onImageUpload, started } = uploads();
    const onImageBusyChange = vi.fn();
    const { unmount } = render(<Controlled initial="본문" onImageUpload={onImageUpload} onImageBusyChange={onImageBusyChange} />);
    const t = await area();

    dropAt(t, [png('shot.png')], 0);
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`${SHOT}본문`);
    });
    await waitFor(() => {
      expect(onImageBusyChange.mock.calls).toEqual([[true], [false]]);
    });

    unmount();
    await settled();
    expect(onImageBusyChange.mock.calls).toEqual([[true], [false]]);
  });
});

describe('MarkdownEditor 이미지 단추와 파일 입력', () => {
  test('S5-AC-54 MarkdownEditor 이미지, tools가 빔: onImageUpload가 있어도 툴바, 이미지 단추, 파일 입력이 없고 놓은 이미지는 올린다', async () => {
    const { onImageUpload, started } = uploads();
    render(<Controlled tools={[]} initial="본문" onImageUpload={onImageUpload} />);
    const t = await area();

    expect(screen.queryByRole('toolbar')).toBeNull();
    expect(screen.queryByRole('button', { name: '이미지' })).toBeNull();
    expect(screen.queryAllByLabelText('이미지'), '이름이 "이미지"인 파일 입력').toEqual([]);

    dropAt(t, [png('shot.png')], 2);
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(`본문${SHOT}`);
    });
  });

  test('S5-AC-54 MarkdownEditor 이미지 파일 입력: accept는 PNG, JPEG, GIF, WebP 넷뿐이고 여러 파일을 고를 수 있으며, 이름은 단추와 같다', async () => {
    const { onImageUpload } = uploads();
    render(<Controlled tools={['bold']} onImageUpload={onImageUpload} imageLabel="그림" />);
    const button = await found(() => screen.queryByRole('button', { name: '그림' }), '툴바 버튼 "그림"');
    const input = must(imageInputOf(button), '"그림" 단추와 이어진 파일 입력');

    const accept = (input.getAttribute('accept') ?? '').split(',').map((type) => type.trim());
    expect([...accept].sort()).toEqual(['image/gif', 'image/jpeg', 'image/png', 'image/webp']);
    expect(input.multiple).toBe(true);
    expect(input).toBeEnabled();
    expect(within(must(button.parentElement, '툴바')).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['굵게', '그림']);
    expect(screen.getAllByLabelText('그림')).toContain(input);
  });

  test('S5-AC-54 MarkdownEditor 이미지 파일 입력: 같은 파일을 다시 골라도 또 올린다', async () => {
    const { onImageUpload, names, started } = uploads();
    render(<Controlled initial="" onImageUpload={onImageUpload} />);
    const user = userEvent.setup();
    const t = await area();
    const input = await imageInput();
    const shot = png('shot.png');

    await user.upload(input, shot);
    await (await started(1)).answer({ ok: true, id: ID });
    await waitFor(() => {
      expect(t).toHaveValue(SHOT);
    });
    expect(input.value, '고른 뒤 입력을 비운다').toBe('');

    await user.upload(input, shot);
    await (await started(2)).answer({ ok: true, id: ID_2 });
    await waitFor(() => {
      expect(t.value).toContain(syntax('shot.png', ID_2));
    });
    expect(names()).toEqual(['shot.png', 'shot.png']);
  });
});
