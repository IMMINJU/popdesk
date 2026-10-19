// b5 묶음 B5-AC-09: 공용 컴포넌트는 props를 바꾸지 않고, 박혀 있던 한국어 기본 글을 화면 언어의 글로 바꾼다.
// 앱이 글을 넘기지 않은 경우만 본다(넘긴 글은 그대로 쓴다). 화면 언어는 `<html lang>`으로 준다
// (RelativeTime이 이미 그것을 따른다). 가정: @popdesk/ui는 앱을 모르니 화면 언어를 `<html lang>`에서 읽는다.
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { renderMarkdown } from '../markdown';
import { found, text } from '../__tests__/support';
import { AttachmentList, type AttachmentItem } from './AttachmentList';
import { LogoField } from './LogoField';
import { MarkdownEditor, type ImageUploadResult } from './MarkdownEditor';
import { SearchField } from './SearchField';

const HANGUL = /[가-힣ㄱ-ㅎㅏ-ㅣ]/;
const ATTRIBUTES = ['aria-label', 'title', 'placeholder', 'alt'] as const;
const AT = '2026-10-08T03:15:00Z';
const IMAGE_ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';

/** Every piece of Korean under `root`: text nodes and the attributes a screen reader or a pointer shows. */
function hangulIn(root: Element): string[] {
  const out: string[] = [];
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      const said = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (HANGUL.test(said)) out.push(`글 "${said}"`);
      return;
    }
    if (node instanceof Element) {
      for (const name of ATTRIBUTES) {
        const value = node.getAttribute(name);
        if (value !== null && HANGUL.test(value)) out.push(`${name}="${value}" <${node.tagName.toLowerCase()}>`);
      }
    }
    node.childNodes.forEach(walk);
  };
  walk(root);
  return out;
}

const expectNoHangul = (what: string, root: Element = document.body) => {
  expect(hangulIn(root), `${what}: 영어 화면에 남은 한글`).toEqual([]);
};

beforeEach(() => {
  document.documentElement.lang = 'en';
});
afterEach(() => {
  document.documentElement.lang = '';
});

function Editor(props: { onImageUpload?: (file: File) => Promise<ImageUploadResult> }) {
  const [value, setValue] = useState('');
  return <MarkdownEditor label="Description" minHeight={420} value={value} onChange={setValue} maxLength={1000} showCount onImageUpload={props.onImageUpload} />;
}

describe('공용 컴포넌트의 기본 글 (b5 묶음)', () => {
  test('B5-AC-09 MarkdownEditor 쓰기: 툴바 이름, 서식 이름, 탭, 이미지 단추에 한글이 없다', async () => {
    render(<Editor onImageUpload={() => new Promise<ImageUploadResult>(() => undefined)} />);
    await found(() => screen.queryByRole('toolbar'), '툴바');

    expectNoHangul('MarkdownEditor 쓰기');
  });

  test('B5-AC-09 MarkdownEditor 미리 보기: 빈 미리 보기 글이 영어다', async () => {
    const user = userEvent.setup();
    render(<Editor />);
    const preview = await found(() => screen.queryAllByRole('tab')[1], '두째 탭(미리 보기)');
    await user.click(preview);

    await found(() => (preview.getAttribute('aria-selected') === 'true' ? preview : null), '미리 보기 탭이 골라짐');
    expectNoHangul('MarkdownEditor 빈 미리 보기');
  });

  test('B5-AC-09 MarkdownEditor 이미지 올리는 중과 이미지가 아닌 파일: 아래 줄이 영어다', async () => {
    const user = userEvent.setup({ applyAccept: false });
    render(<Editor onImageUpload={() => new Promise<ImageUploadResult>(() => undefined)} />);
    const input = await found(() => document.querySelector<HTMLInputElement>('input[type="file"]'), '이미지 파일 칸');
    await user.upload(input, new File(['a'], 'shot.png', { type: 'image/png' }));
    await found(() => (text(document.body).includes('shot.png') ? document.body : null), '올리는 중 줄');
    expectNoHangul('MarkdownEditor 올리는 중');

    const area = screen.getByRole('textbox', { name: 'Description' });
    fireEvent.drop(area, { dataTransfer: { files: [new File(['b'], 'notes.txt', { type: 'text/plain' })], types: ['Files'] } });
    expectNoHangul('MarkdownEditor 이미지가 아닌 파일');
  });

  test('B5-AC-09 SearchField: 검색어가 있으면 보이는 지우기 단추의 이름이 영어다', async () => {
    render(<SearchField label="Search issues" value="printer" onSearch={() => undefined} />);
    await found(() => screen.queryAllByRole('button')[0], '지우기 단추');

    expectNoHangul('SearchField');
  });

  test.each([
    ['없음, 다른 테마 로고를 같이 씀', { logo: null, fallback: { url: 'http://x/light.png', from: 'light' as const } }],
    ['둘 다 없음', { logo: null, fallback: null }],
    ['올리는 중', { logo: null, uploading: true }],
  ])('B5-AC-09 LogoField %s: 기본 글이 영어다', (what, over) => {
    render(
      <LogoField
        theme="dark"
        label="Dark theme logo"
        onUpload={() => undefined}
        onRemove={() => undefined}
        uploadLabel="Upload"
        changeLabel="Change"
        removeLabel="Remove"
        {...over}
      />,
    );

    expectNoHangul(`LogoField ${what}`);
  });

  test('B5-AC-09 AttachmentList: 본문 이미지, 내부, 지운 흔적, 지우기 단추의 기본 글이 영어다', () => {
    const items: AttachmentItem[] = [
      { id: 'a1', name: 'shot.png', size: 2048, href: 'http://x/a1', uploaderLabel: 'Kim Customer', createdAt: AT, inline: true },
      { id: 'a2', name: 'log.txt', size: 1024, href: 'http://x/a2', uploaderLabel: 'Lee Agent', createdAt: AT, internal: true, canDelete: true },
      { id: 'a3', name: '', size: 0, uploaderLabel: 'Lee Agent', createdAt: AT, deleted: true },
    ];
    render(<AttachmentList title="Attachments" emptyText="No attachments" items={items} onDelete={() => undefined} />);

    expectNoHangul('AttachmentList');
  });

  test('B5-AC-09 markdown: 볼 수 없는 이미지 자리의 기본 글이 영어다', async () => {
    render(<div data-testid="md">{renderMarkdown(`![screen](attachment:${IMAGE_ID})`, { imageSrc: () => 'http://x/broken.png' })}</div>);
    const img = await found(() => screen.getByTestId('md').querySelector('img'), '본문 이미지');
    act(() => {
      fireEvent.error(img);
    });

    await found(() => (text(screen.getByTestId('md')) !== '' ? screen.getByTestId('md') : null), '볼 수 없음 글');
    expectNoHangul('markdown 볼 수 없는 이미지');
  });
});
