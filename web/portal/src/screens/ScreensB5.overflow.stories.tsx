// b5 묶음(화면 전체의 영어) acceptance.md 5절 끝: 영어 글이 한국어보다 길어 글이 긴 포털 화면 P-5 문의 상세를 영어 계정
// (`Me.locale: 'en'`)으로 820·1280 폭, 밝게 그리고, play에서 테마가 그것인지, 페이지와 화면 본문이 가로로 넘치지 않는지,
// axe(대비 포함) 위반이 없는지 본다. 그 뒤 `<html lang>`이 en이고 화면에 한글이 없는지 본다(B5-AC-04, B5-AC-05).
// 견본: 긴 영어 제목, 긴 고객사 이름, 오십 자 이름, 긴 파일 이름, 고친 댓글과 지운 댓글, 지원팀 댓글. 영어 문구로 찾지 않는다
// (영어 글은 구현 탭이 짓는다, 4절 6): 화면이 그려진 것은 견본 데이터로 기다린다.
// 화면은 가짜 fetch(__tests__/storyFetchB5.ts)의 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ISSUE_EN, LONG_COMPANY_EN, LONG_FILE_EN, LONG_PERSON_EN, LONG_TITLE_EN } from '../__tests__/storyFetchB5';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../portal.css';
import { at, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
import { App } from '../App';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/b5 포털 영어 화면' };
export default meta;

const HANGUL = /[가-힣ㄱ-ㅎㅏ-ㅣ]/;
const ATTRIBUTES = ['aria-label', 'title', 'placeholder', 'alt'] as const;
const WORDS = [LONG_TITLE_EN, LONG_COMPANY_EN, LONG_PERSON_EN, LONG_FILE_EN];

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** Throws unless the page is English: `<html lang="en">` and no Hangul in its text or in the attributes people are shown. */
function expectEnglish(what: string): void {
  if (document.documentElement.lang !== 'en') throw new Error(`${what}: <html lang>이 en이 아니다 (${document.documentElement.lang})`);
  const found: string[] = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const parent = node.parentElement;
    if (parent && (parent.tagName === 'SCRIPT' || parent.tagName === 'STYLE')) continue;
    const said = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
    if (HANGUL.test(said)) found.push(`"${said}"`);
  }
  for (const el of Array.from(document.body.querySelectorAll('*'))) {
    for (const name of ATTRIBUTES) {
      const value = el.getAttribute(name);
      if (value !== null && HANGUL.test(value)) found.push(`${name}="${value}"`);
    }
  }
  if (found.length > 0) throw new Error(`${what}: 영어 화면에 남은 한글 ${String(found.length)}곳: ${found.slice(0, 20).join(' / ')}`);
}

function screenStory(width: Width, theme: Theme): Story {
  const { parameters, globals } = at(width, theme, 'portal');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      window.history.replaceState(null, '', `/issues/${ISSUE_EN.key}`);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      const main = await waitFor(() => mainOf(canvasElement), 'P-5의 main', 8000);
      await waitFor(() => main.querySelector('h1'), 'P-5의 h1', 8000);
      for (const word of WORDS) {
        await waitFor(() => (textOf(document.body).includes(word) ? true : null), `P-5의 견본 "${word}"`, 8000);
      }
      await expectDrawnRight(width, theme, [{ el: main, what: 'P-5의 main' }]);
      expectEnglish('P-5');
    },
  };
}

export const P5Detail820 = screenStory(820, 'light');
export const P5Detail1280 = screenStory(1280, 'light');
