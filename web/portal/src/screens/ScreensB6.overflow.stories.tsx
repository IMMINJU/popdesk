// b6 묶음 screens.md 3절: 포털 P-3 문의 목록의 필터 줄을 1280·820 폭, 밝게·어둡게, 한국어·영어 고객으로 그리고(B6-AC-04),
// play에서 "내가 올린 문의만" 체크(영어 글은 i18n 표에서 읽는다) 위에 빈 칸이 없는지 본다.
// 필터 줄의 칸(.pd-filters의 자식, display: contents인 .pd-filters-more는 그 자식)을 위아래로 겹치는 것끼리 묶어 줄로 나누고,
//   - 체크가 선 줄에 입력 칸(검색 칸의 input, 고객사 Select, 상태 체크 묶음의 선택지 칸)이 있으면 체크의 아래쪽이 각 입력 칸의
//     아래쪽과 2px 안쪽으로 맞는지,
//   - 체크가 그 줄의 첫 칸이면 앞 줄의 아래쪽과의 틈이 줄 사이 틈(--space-3의 계산값)보다 2px 넘게 크지 않은지
// 본다. 그 뒤 페이지와 화면 본문이 넘치지 않는지, 테마와 axe를 본다(expectDrawnRight).
// 고객은 고객사 둘에 속해 고객사 필터도 선다. 체크가 어느 줄에 섰는지는 실패 글에 적는다.
// 화면은 가짜 fetch(__tests__/storyFetchB6.ts)의 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { setLanguage } from '../__tests__/storyFetchB6';
import type { ReactElement } from 'react';
import { messages } from '@popdesk/i18n';
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

const meta = { title: 'Overflow/b6 포털 필터 줄' };
export default meta;

type Language = 'ko' | 'en';

/** How far two edges may differ and still count as lined up (B6-AC-04). */
const SLACK = 2;

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** The parts that stand in the filter row: its children, the folding box's children in place of the box. */
function partsOf(filters: Element): Element[] {
  return Array.from(filters.children).flatMap((el) => (el.classList.contains('pd-filters-more') ? Array.from(el.children) : [el]));
}

/** The parts put into the lines they stand in, top line first, each line left to right. */
function linesOfRow(parts: readonly Element[]): Element[][] {
  const sorted = [...parts].filter((el) => el.getBoundingClientRect().height > 0).sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
  const lines: { bottom: number; parts: Element[] }[] = [];
  for (const el of sorted) {
    const box = el.getBoundingClientRect();
    const last = lines[lines.length - 1];
    if (last && box.top < last.bottom - 1) {
      last.parts.push(el);
      last.bottom = Math.max(last.bottom, box.bottom);
    } else {
      lines.push({ bottom: box.bottom, parts: [el] });
    }
  }
  return lines.map((l) => l.parts.sort((a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left));
}

/** The box people type or choose in, inside a part of the row: the search input, a select, or the options of a group. */
function inputBoxOf(part: Element): Element | null {
  if (part.classList.contains('pd-check') || part.classList.contains('pd-filters-clear')) return null;
  return part.querySelector('.pd-group-options') ?? part.querySelector('input:not([type="checkbox"]):not([type="radio"]), select, button');
}

/** `--space-3` as the page computes it, in px. */
function rowGap(): number {
  const probe = document.createElement('div');
  probe.style.height = 'var(--space-3)';
  document.body.append(probe);
  const px = probe.getBoundingClientRect().height;
  probe.remove();
  return px;
}

/** Throws unless the check stands with no empty space above it (B6-AC-04). */
function expectCheckFits(filters: Element, check: Element): void {
  const lines = linesOfRow(partsOf(filters));
  const at = lines.findIndex((line) => line.includes(check));
  if (at < 0) throw new Error('P-3 필터 줄에서 체크를 찾지 못했다');
  const where = `${String(window.innerWidth)} 폭, 필터 ${String(lines.length)}줄 중 ${String(at + 1)}째 줄`;
  const line = lines[at] ?? [];
  const checkBox = check.getBoundingClientRect();
  const inputs = line.map(inputBoxOf).filter((el): el is Element => el !== null);
  const wrong: string[] = [];
  if (inputs.length > 0) {
    for (const input of inputs) {
      const off = checkBox.bottom - input.getBoundingClientRect().bottom;
      if (Math.abs(off) > SLACK) wrong.push(`체크의 아래쪽이 같은 줄 입력 칸(${input.tagName.toLowerCase()}.${input.className})의 아래쪽과 ${off.toFixed(1)}px 어긋난다`);
    }
  }
  if (line[0] === check && at > 0) {
    const above = Math.max(...(lines[at - 1] ?? []).map((el) => el.getBoundingClientRect().bottom));
    const gap = checkBox.top - above;
    const allowed = rowGap();
    if (gap > allowed + SLACK) wrong.push(`줄의 첫 칸인 체크와 앞 줄 사이 틈이 ${gap.toFixed(1)}px다(줄 사이 틈 ${allowed.toFixed(1)}px)`);
  }
  if (inputs.length === 0 && line[0] !== check) wrong.push('체크가 입력 칸 없는 줄의 첫 칸이 아닌 자리에 섰다');
  if (wrong.length > 0) throw new Error(`P-3 체크 위 빈 칸 (${where}): ${wrong.join(' / ')}`);
}

function filterStory(width: Width, theme: Theme, language: Language): Story {
  const { parameters, globals } = at(width, theme, 'portal');
  const mineOnly = messages[language]['portalIssues.mineOnly'];
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setLanguage(language);
      window.history.replaceState(null, '', '/issues');
      return <App />;
    },
    play: async ({ canvasElement }) => {
      const main = await waitFor(() => mainOf(canvasElement), 'P-3의 main', 8000);
      await waitFor(() => (document.documentElement.lang === language ? true : null), `P-3의 <html lang> ${language}`, 8000);
      const filters = await waitFor(() => main.querySelector('.pd-filters'), 'P-3의 필터 줄', 8000);
      const check = await waitFor(
        () => Array.from(filters.querySelectorAll('.pd-check')).find((c) => textOf(c.querySelector('label')) === mineOnly),
        `P-3의 체크 "${mineOnly}"`,
        8000,
      );
      await waitFor(() => filters.querySelector('.pd-filters-more [data-pd="Select"], .pd-filters-more select'), 'P-3의 고객사 필터', 8000);
      expectCheckFits(filters, check);
      await expectDrawnRight(width, theme, [{ el: main, what: 'P-3의 main' }]);
    },
  };
}

export const P3Filters1280LightKo = filterStory(1280, 'light', 'ko');
export const P3Filters1280DarkKo = filterStory(1280, 'dark', 'ko');
export const P3Filters820LightKo = filterStory(820, 'light', 'ko');
export const P3Filters820DarkKo = filterStory(820, 'dark', 'ko');
export const P3Filters1280LightEn = filterStory(1280, 'light', 'en');
export const P3Filters1280DarkEn = filterStory(1280, 'dark', 'en');
export const P3Filters820LightEn = filterStory(820, 'light', 'en');
export const P3Filters820DarkEn = filterStory(820, 'dark', 'en');
