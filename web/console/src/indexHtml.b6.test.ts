// b6 묶음 screens.md 5절: 콘솔의 index.html이 첫 로드 동안 보이는 탭 제목은 언어가 없는 "popdesk"다(B6-AC-06).
// 화면이 그려진 뒤의 제목(useDocumentTitle)은 이 파일이 보지 않는다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const HANGUL = /[가-힣ㄱ-ㅎㅏ-ㅣ]/;

describe('콘솔 index.html (b6 묶음)', () => {
  it('B6-AC-06 콘솔 index.html의 <title>은 하나이고 "popdesk"이며 한글이 없다', () => {
    const html = readFileSync(resolve(import.meta.dirname, '../index.html'), 'utf8');
    const page = new DOMParser().parseFromString(html, 'text/html');
    const titles = Array.from(page.querySelectorAll('title'), (t) => t.textContent);

    expect(titles).toEqual(['popdesk']);
    expect(HANGUL.test(titles.join(' '))).toBe(false);
  });
});
