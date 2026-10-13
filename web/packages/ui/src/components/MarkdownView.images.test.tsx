// 5번 묶음 screens.md 2절 그리기. `imageSrc`를 받은 MarkdownView만 `![설명](attachment:{UUID})`를 이미지로 그린다(S5-AC-50).
// 그 밖의 이미지 문법은 지금처럼 글자다(S5-AC-51, D-59). 읽지 못하면 설명과 "이미지를 볼 수 없습니다"(S5-AC-52).
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, test, vi } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { MarkdownView } from './MarkdownView';

const ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const ID_2 = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e02';
const UNREADABLE = '이미지를 볼 수 없습니다';

/** The address an app would give: the attachment path of one issue. */
const src = (id: string) => `/api/test/issues/ACME-7/attachments/${id}`;

const image = (name: string) => found(() => screen.queryByRole('img', { name }), `이미지 "${name}"`);

/** Draws `source` with an address function that answers every ID, and waits for the image that proves images are on. */
async function drawnWithProof(source: string) {
  const imageSrc = vi.fn(src);
  const view = render(<MarkdownView source={`![증거](attachment:${ID_2})\n\n${source}`} imageSrc={imageSrc} />);
  await image('증거');
  return { ...view, imageSrc };
}

describe('MarkdownView 이미지', () => {
  test('S5-AC-50 MarkdownView 이미지: imageSrc를 주면 ![설명](attachment:{UUID})가 img가 된다. 이름은 설명, src는 함수가 준 주소, loading="lazy"', async () => {
    const imageSrc = vi.fn(src);
    const { container } = render(<MarkdownView source={`오류 화면입니다.\n\n![오류 화면](attachment:${ID})\n\n다시 해 봤습니다.`} imageSrc={imageSrc} />);

    const img = await image('오류 화면');
    expect(img.tagName).toBe('IMG');
    expect(img).toHaveAttribute('alt', '오류 화면');
    expect(img).toHaveAttribute('src', src(ID));
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(imageSrc).toHaveBeenCalledWith(ID);
    expect(text(container), '문법의 글자는 남지 않는다').not.toContain('attachment:');
    expect(text(container)).toContain('오류 화면입니다.');
    expect(text(container)).toContain('다시 해 봤습니다.');
  });

  test('S5-AC-50 MarkdownView 이미지: 한 줄에 둘이면 둘 다 그리고 사이의 글자는 그대로다', async () => {
    const { container } = render(<MarkdownView source={`앞 ![하나](attachment:${ID}) 가운데 ![둘](attachment:${ID_2}) 뒤`} imageSrc={src} />);

    expect(await image('하나')).toHaveAttribute('src', src(ID));
    expect(await image('둘')).toHaveAttribute('src', src(ID_2));
    expect(text(container)).toBe('앞 가운데 뒤');
  });

  test('S5-AC-50 MarkdownView 이미지: 설명이 비면 이름 없는 img(alt="")다', async () => {
    const { container } = await drawnWithProof(`![](attachment:${ID})`);

    const img = container.querySelector(`img[src="${src(ID)}"]`);
    expect(img, '설명 없는 이미지').not.toBeNull();
    expect(img).toHaveAttribute('alt', '');
  });

  test('S5-AC-50 MarkdownView 이미지: 함수가 null을 주면 그 이미지만 글자로 남는다', async () => {
    const { container } = render(
      <MarkdownView source={`![그린다](attachment:${ID})\n\n![안 그린다](attachment:${ID_2})`} imageSrc={(id: string) => (id === ID ? src(id) : null)} />,
    );

    await image('그린다');
    expect(container.querySelectorAll('img')).toHaveLength(1);
    expect(text(container)).toContain('안 그린다');  });

  test('S5-AC-50 MarkdownView 이미지: imageSrc가 없으면 지금처럼 글자다(같은 글을 imageSrc와 함께 그리면 이미지다)', async () => {
    const source = `![오류 화면](attachment:${ID})`;
    const { container, rerender } = render(<MarkdownView source={source} />);
    expect(container.querySelectorAll('img')).toHaveLength(0);
    // What the reader draws today for an image syntax: the "!" and the description (the link part with an address that is not allowed).
    expect(text(container)).toBe('!오류 화면');

    rerender(<MarkdownView source={source} imageSrc={src} />);
    await image('오류 화면');
  });

  it.each<[string, string]>([
    ['짧다', '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e0'],
    ['대문자가 섞였다', '7F1C2A9E-0c4b-4d8e-9a51-3b2f6c1d0e01'],
    ['뒤에 글자가 더 있다', `${ID}x`],
    ['뒤에 경로가 더 있다', `${ID}/../${ID_2}`],
    ['뒤에 쿼리가 더 있다', `${ID}?v=1`],
    ['줄표가 없다', '7f1c2a9e0c4b4d8e9a513b2f6c1d0e01'],
    ['앞에 빈칸이 있다', ` ${ID}`],
    ['attachment: 뒤가 주소다', 'https://images.example/a.png'],
    ['attachment: 뒤가 javascript:다', 'javascript:alert(1)'],
    ['비었다', ''],
  ])('S5-AC-50 MarkdownView 이미지: attachment: 뒤가 UUID 꼴이 아니면 글자다: %s', async (_why, target) => {
    const { container, imageSrc } = await drawnWithProof(`![설명](attachment:${target})`);

    expect(container.querySelectorAll('img'), '증거 이미지 하나뿐').toHaveLength(1);
    expect(screen.queryByRole('img', { name: '설명' })).toBeNull();
    expect(imageSrc.mock.calls.map(([id]) => id), '주소 함수는 UUID 꼴인 ID만 받는다').toEqual([ID_2]);
    expect(text(container)).toContain('설명');
  });

  it.each<[string, string]>([
    ['http 주소의 이미지 문법', '![추적](http://images.example/pixel.png)'],
    ['https 주소의 이미지 문법', '![추적](https://images.example/pixel.png)'],
    ['data: 주소의 이미지 문법', '![점](data:image/png;base64,iVBORw0KGgo=)'],
    ['프로토콜 없는 주소의 이미지 문법', '![추적](//images.example/pixel.png)'],
    ['같은 서버의 경로를 적은 이미지 문법', `![남의 것](/api/test/issues/BETA-1/attachments/${ID})`],
    ['날 <img>', '<img src="https://images.example/pixel.png" alt="추적">'],
    ['날 <img>의 attachment: 주소', `<img src="attachment:${ID}" alt="설명">`],
    ['날 <img onerror>', '<img src=x onerror="alert(1)">'],
  ])('S5-AC-51 MarkdownView 이미지: imageSrc가 있어도 %s은 img를 만들지 않는다', async (_what, source) => {
    const { container, imageSrc } = await drawnWithProof(source);

    expect(container.querySelectorAll('img'), '증거 이미지 하나뿐').toHaveLength(1);
    expect(container.querySelectorAll('[onerror], [src^="http"], [src^="data:"], [src^="//"]')).toHaveLength(0);
    expect(imageSrc.mock.calls.map(([id]) => id)).toEqual([ID_2]);
  });

  test('S5-AC-51 MarkdownView 이미지: 바깥 주소의 이미지 문법은 글자로 남고 그 주소로 가는 요소가 없다', async () => {
    const { container } = await drawnWithProof('![추적](https://images.example/pixel.png)');

    expect(text(container)).toContain('추적');
    expect(container.querySelectorAll('[src*="images.example"], [srcset], picture, source, object, embed')).toHaveLength(0);
  });

  test('S5-AC-52 MarkdownView 이미지: 읽지 못하면(error) img가 사라지고 설명과 "이미지를 볼 수 없습니다"가 보인다. 다른 이미지는 그대로다', async () => {
    const { container } = render(<MarkdownView source={`![오류 화면](attachment:${ID})\n\n![설정 화면](attachment:${ID_2})`} imageSrc={src} />);
    const broken = await image('오류 화면');
    await image('설정 화면');
    expect(text(container)).not.toContain(UNREADABLE);

    fireEvent.error(broken);

    await found(() => (text(container).includes(UNREADABLE) ? container : null), `"${UNREADABLE}"`);
    expect(container.querySelector(`img[src="${src(ID)}"]`), '읽지 못한 img').toBeNull();
    expect(container.querySelectorAll('img'), '남은 img는 읽힌 것 하나').toHaveLength(1);
    expect(text(container)).toContain('오류 화면');
    expect(await image('설정 화면')).toHaveAttribute('src', src(ID_2));
    expect(text(container).split(UNREADABLE), '읽지 못한 것은 하나').toHaveLength(2);
  });

  test('S5-AC-52 MarkdownView 이미지: 설명 없는 이미지를 읽지 못하면 "이미지를 볼 수 없습니다"만 보인다', async () => {
    const { container } = render(<MarkdownView source={`![](attachment:${ID})`} imageSrc={src} />);
    const broken = await found(() => container.querySelector('img'), '설명 없는 이미지');

    fireEvent.error(broken);

    await found(() => (text(container) === UNREADABLE ? container : null), `글자가 "${UNREADABLE}"뿐`);
    expect(container.querySelectorAll('img')).toHaveLength(0);
  });

  test('S5-AC-50 MarkdownView 이미지: 링크 안의 이미지는 링크 안에 그린다', async () => {
    render(<MarkdownView source={`[![오류 화면](attachment:${ID})](https://example.com/help)`} imageSrc={src} />);

    const img = await image('오류 화면');
    expect(img).toHaveAttribute('src', src(ID));
    const a = img.closest('a');
    expect(a, '이미지를 감싼 링크').not.toBeNull();
    expect(a).toHaveAttribute('href', 'https://example.com/help');
    expect(a).toHaveAttribute('target', '_blank');
  });

  test('S5-AC-50 MarkdownView 이미지: 코드 안의 이미지 문법은 글자 그대로다(코드 조각과 코드 블록)', async () => {
    const syntax = `![오류 화면](attachment:${ID})`;
    const { container } = await drawnWithProof(`코드 조각 \`${syntax}\`\n\n\`\`\`\n${syntax}\n\`\`\``);

    expect(container.querySelectorAll('img'), '증거 이미지 하나뿐').toHaveLength(1);
    const span = Array.from(container.querySelectorAll('code')).find((code) => code.closest('pre') === null);
    expect(span?.textContent, '코드 조각').toBe(syntax);
    expect(container.querySelector('pre code')?.textContent.trim()).toBe(syntax);
  });

  test('S5-AC-50 MarkdownView 이미지: 역슬래시로 막은 느낌표 문법(\\![…])은 이미지가 아니다', async () => {
    const { container } = await drawnWithProof(`\\!\\[설명\\](attachment:${ID})`);

    expect(container.querySelectorAll('img'), '증거 이미지 하나뿐').toHaveLength(1);
    expect(text(container)).toContain(`![설명](attachment:${ID})`);
  });

  test('S5-AC-50 MarkdownView 이미지 axe: 읽힌 이미지와 읽지 못한 이미지에 접근성 위반이 없다', async () => {
    render(<MarkdownView source={`![오류 화면](attachment:${ID})\n\n![설정 화면](attachment:${ID_2})`} imageSrc={src} />);
    fireEvent.error(await image('오류 화면'));
    await found(() => (text(document.body).includes(UNREADABLE) ? document.body : null), `"${UNREADABLE}"`);

    await expectNoAxeViolations();
  });
});
