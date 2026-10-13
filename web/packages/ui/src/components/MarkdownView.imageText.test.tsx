// 5번 묶음 검증(C-10)의 변이 테스트에서 살아남은 것을 고정한다. 이미지로 그리지 않는 문법은 imageSrc가
// 없을 때와 같은 글자다(S5-AC-50, 51). 설명의 역슬래시는 빠진다. 읽지 못한 자리의 글은 설명, 빈칸, 문구다(S5-AC-52).
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, test } from 'vitest';
import { found, must, text } from '../__tests__/support';
import { MarkdownView } from './MarkdownView';

const ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const ID_2 = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e02';
const UNREADABLE = '이미지를 볼 수 없습니다';

const src = (id: string) => `/api/test/issues/ACME-7/attachments/${id}`;

const image = (name: string) => found(() => screen.queryByRole('img', { name }), `이미지 "${name}"`);

/** What the reader draws for `source` when it draws no image at all. */
function asText(source: string): string {
  const { container, unmount } = render(<MarkdownView source={source} />);
  const drawn = text(container);
  unmount();
  return drawn;
}

describe('MarkdownView 이미지의 글자', () => {
  it.each<[string, string]>([
    ['UUID 꼴이 아닌 attachment:', '![설명](attachment:7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0g01)'],
    ['빈 attachment:', '![설명](attachment:)'],
    ['바깥 주소', '![설명](https://images.example/pixel.png)'],
    ['같은 서버의 경로', `![설명](/api/test/issues/BETA-1/attachments/${ID})`],
    ['닫는 괄호가 없는 것', `![설명](attachment:${ID}`],
    ['주소 괄호가 없는 것', '![설명] 뒤의 글'],
    ['닫는 대괄호가 없는 것', '![설명 뒤의 글'],
    ['느낌표와 대괄호뿐인 것', '끝입니다![]'],
  ])('S5-AC-51 MarkdownView 이미지의 글자: %s은 imageSrc가 없을 때와 같은 글자이고 읽지 못함 문구가 없다', async (_what, source) => {
    const expected = asText(`증거\n\n${source}`);

    const { container } = render(<MarkdownView source={`![증거](attachment:${ID_2})\n\n증거\n\n${source}`} imageSrc={src} />);
    await image('증거');

    expect(text(container)).toBe(expected);
    expect(text(container)).not.toContain(UNREADABLE);
    expect(container.querySelectorAll('img'), '증거 이미지 하나뿐').toHaveLength(1);
  });

  test('S5-AC-50 MarkdownView 이미지의 글자: 함수가 null을 준 이미지는 imageSrc가 없을 때와 같은 글자다', async () => {
    const source = `![안 그린다](attachment:${ID_2})`;
    const expected = asText(source);

    const { container } = render(
      <MarkdownView source={`![그린다](attachment:${ID})\n\n${source}`} imageSrc={(id: string) => (id === ID ? src(id) : null)} />,
    );
    await image('그린다');

    expect(text(container)).toBe(expected);
  });

  it.each<[string, string, string]>([
    ['대괄호 앞의 역슬래시', String.raw`a\[1\].png`, 'a[1].png'],
    ['별표 앞의 역슬래시', String.raw`2 \* 3`, '2 * 3'],
    ['역슬래시 앞의 역슬래시', String.raw`C:\\temp`, String.raw`C:\temp`],
    ['글자 앞의 역슬래시는 남는다', String.raw`C:\temp`, String.raw`C:\temp`],
  ])('S5-AC-50 MarkdownView 이미지의 글자: 설명에서 %s', async (_what, label, name) => {
    render(<MarkdownView source={`![${label}](attachment:${ID})`} imageSrc={src} />);

    const img = await image(name);
    expect(img).toHaveAttribute('alt', name);
    expect(img).toHaveAttribute('src', src(ID));
  });

  test('S5-AC-52 MarkdownView 이미지의 글자: 읽지 못한 자리는 설명, 빈칸, 문구 차례다', async () => {
    const { container } = render(<MarkdownView source={`![오류 화면](attachment:${ID})`} imageSrc={src} />);

    fireEvent.error(await image('오류 화면'));

    await found(() => (text(container).includes(UNREADABLE) ? container : null), `"${UNREADABLE}"`);
    expect(text(container)).toBe(`오류 화면 ${UNREADABLE}`);
  });

  test('S5-AC-52 MarkdownView 이미지의 글자: 설명이 빈 이미지를 읽지 못하면 문구만 있고 빈 설명 칸이 없다', async () => {
    const { container } = render(<MarkdownView source={`![](attachment:${ID})`} imageSrc={src} />);
    const img = await found(() => container.querySelector(`img[src="${src(ID)}"]`), '설명 없는 이미지');

    fireEvent.error(img);

    await found(() => (text(container).includes(UNREADABLE) ? container : null), `"${UNREADABLE}"`);
    const place = must(container.querySelector('p'), '문단');
    expect(place.textContent, '앞뒤에 빈칸이 없다').toBe(UNREADABLE);
    expect(Array.from(place.querySelectorAll('span')).filter((span) => span.textContent === ''), '빈 칸').toHaveLength(0);
  });
});
