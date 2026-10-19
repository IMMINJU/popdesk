// Support for the overflow stories (`*.overflow.stories.tsx`, 6a 묶음 screens.md 6절, S6A-AC-85·91).
//
// A story is drawn at one width (360, 820 or 1280) and one theme, and its `play` asserts that
// neither the page nor the parts it names scroll sideways (`scrollWidth <= clientWidth`), the
// check `MarkdownView.stories.tsx` `LargeImage` began.
//
// The width is the Storybook viewport of the story (`globals.viewport`). The story runner
// (`pnpm test-storybook`, @storybook/addon-vitest) sets the browser page to that size before the
// story renders, and the Storybook panel draws its frame at that size, so media queries see the
// width. `expectPageFits` first checks that the frame is that wide, so a story that lost its
// width fails instead of passing at 1200px.
//
// No `storybook/test` here: the console has no Storybook packages, so its stories import this file
// and fail by throwing.
//
// After the widths, `play` checks the theme (`<html data-theme>`) and runs axe with the contrast
// rule on (`expectDrawnRight`).
import axe from 'axe-core';

export const WIDTHS = [360, 820, 1280] as const;
export type Width = (typeof WIDTHS)[number];
export type Theme = 'light' | 'dark';

const VIEWPORTS = {
  w360: { name: '360', styles: { width: '360px', height: '800px' }, type: 'mobile' },
  w820: { name: '820', styles: { width: '820px', height: '1000px' }, type: 'tablet' },
  w1280: { name: '1280', styles: { width: '1280px', height: '900px' }, type: 'desktop' },
} as const;

/** `parameters` and `globals` of a story drawn at `width` in `theme`; `density` for the console. */
export function at(width: Width, theme: Theme, density: 'portal' | 'console' = 'portal') {
  return {
    parameters: { viewport: { options: VIEWPORTS } },
    globals: { viewport: { value: `w${String(width)}`, isRotated: false }, theme, density },
  };
}

/** Waits until `find` returns something and returns it; throws, naming `what`, after `ms`. */
export async function waitFor<T>(find: () => T | null | undefined, what: string, ms = 4000): Promise<T> {
  const until = performance.now() + ms;
  for (;;) {
    let found: T | null | undefined;
    let error: unknown;
    try {
      found = find();
    } catch (e) {
      error = e;
    }
    if (found !== null && found !== undefined) return found;
    if (performance.now() > until) throw new Error(`${what}: 보이지 않는다${error instanceof Error ? ` (${error.message})` : ''}`);
    await nextFrame();
  }
}

function nextFrame(): Promise<void> {
  return new Promise<void>((resolve) => {
    requestAnimationFrame(() => {
      resolve();
    });
  });
}

/** Throws when `el` scrolls sideways. */
export function expectFits(el: Element, what: string): void {
  if (el.scrollWidth > el.clientWidth) {
    throw new Error(`${what}: 가로로 넘친다 (scrollWidth ${String(el.scrollWidth)} > clientWidth ${String(el.clientWidth)})`);
  }
}

/** Throws unless the frame is `width` wide and the page does not scroll sideways. */
export function expectPageFits(width: Width): void {
  if (window.innerWidth !== width) throw new Error(`틀의 폭이 ${String(width)}이 아니다 (innerWidth ${String(window.innerWidth)})`);
  expectFits(document.documentElement, `${String(width)} 폭의 페이지`);
}

/** Throws unless `<html data-theme>` is `theme`: the story really drew in that theme. */
export function expectTheme(theme: Theme): void {
  const now = document.documentElement.dataset['theme'];
  if (now !== theme) throw new Error(`테마가 ${theme}이 아니다 (data-theme ${String(now)})`);
}

/**
 * axe on the whole page in the real browser, with the contrast rule on (S6A-AC-85): the jsdom
 * tests turn it off. The page rules `region` (content inside landmarks), `landmark-one-main` and
 * `page-has-heading-one` are off: a component story draws its part outside a page, and the screen
 * tests keep those rules in jsdom.
 */
export async function expectNoAxeViolations(): Promise<void> {
  const result = await axe.run(document, {
    rules: {
      'color-contrast': { enabled: true },
      region: { enabled: false },
      'landmark-one-main': { enabled: false },
      'page-has-heading-one': { enabled: false },
    },
  });
  const found = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  if (found.length > 0) throw new Error(`axe 위반 ${String(found.length)}건: ${found.join(' / ')}`);
}

/** The play of every overflow story: the theme, then the width and the parts, then axe. */
export async function expectDrawnRight(width: Width, theme: Theme, parts: readonly { el: Element; what: string }[]): Promise<void> {
  expectTheme(theme);
  await settled();
  expectPageFits(width);
  for (const { el, what } of parts) expectFits(el, what);
  await animationsDone();
  await expectNoAxeViolations();
}

/**
 * Waits until the animations and transitions that end have ended (a dialog fades in from
 * opacity 0, and axe reads contrast from the colors drawn at that moment). Endless ones, such as a
 * spinner, are not waited for.
 */
export async function animationsDone(): Promise<void> {
  const ending = document.getAnimations().filter((a) => a.effect?.getComputedTiming().endTime !== Infinity);
  await Promise.all(ending.map((a) => a.finished.catch(() => undefined)));
  await settled();
}

/** Waits a frame twice, so a layout that changes after the first paint has settled. */
export async function settled(): Promise<void> {
  await nextFrame();
  await nextFrame();
}

/** Text of an element, spaces folded. */
export function textOf(el: Element | null | undefined): string {
  return (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/**
 * How many lines the text inside `el` takes: its text boxes, those that overlap from top to bottom
 * put together (b1 묶음). With `seenOnly`, text inside `.pd-sr-only` is left out: it is not drawn.
 */
export function linesOf(el: Element, seenOnly = false): number {
  const boxes: DOMRect[] = [];
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    if ((node.textContent ?? '').trim() === '') continue;
    if (seenOnly && node.parentElement?.closest('.pd-sr-only')) continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    for (const box of Array.from(range.getClientRects())) if (box.width > 0 && box.height > 0) boxes.push(box);
  }
  boxes.sort((a, b) => a.top - b.top);
  let lines = 0;
  let bottom = -Infinity;
  for (const box of boxes) {
    if (box.top >= bottom - 1) {
      lines += 1;
      bottom = box.bottom;
    } else {
      bottom = Math.max(bottom, box.bottom);
    }
  }
  return lines;
}

/** The `dd` after the `dt` whose text is `term`, inside `root`. */
export function termValue(root: ParentNode, term: string): HTMLElement | null {
  const dt = Array.from(root.querySelectorAll('dt')).find((t) => textOf(t) === term);
  return dt?.nextElementSibling instanceof HTMLElement ? dt.nextElementSibling : null;
}

/** The name of an element from `aria-labelledby`, `aria-label`, or its text, in that order. */
export function nameOf(el: Element): string {
  const by = el.getAttribute('aria-labelledby');
  if (by !== null) return by.split(/\s+/).map((id) => textOf(document.getElementById(id))).join(' ');
  return el.getAttribute('aria-label') ?? textOf(el);
}

/** A button named `name` (see `nameOf`), inside `root`. */
export function buttonNamed(root: ParentNode, name: string): HTMLButtonElement | null {
  return Array.from(root.querySelectorAll('button')).find((b) => nameOf(b) === name) ?? null;
}

/** The "+n" of a `LabelChips` inside `root`: an element with a `title` whose text starts with "+". */
export function moreIn(root: ParentNode): HTMLElement | null {
  return Array.from(root.querySelectorAll<HTMLElement>('[title]')).find((el) => textOf(el).startsWith('+')) ?? null;
}

/** A `fieldset` or `role="group"` / `role="region"` whose name (legend, aria-label, or labelling heading) is `name`. */
export function namedPart(root: ParentNode, name: string | RegExp): HTMLElement | null {
  const named = (el: Element): string => {
    const label = el.getAttribute('aria-label');
    if (label !== null) return label;
    const by = el.getAttribute('aria-labelledby');
    if (by !== null) return by.split(/\s+/).map((id) => textOf(document.getElementById(id))).join(' ');
    return textOf(el.querySelector(':scope > legend'));
  };
  const matches = (s: string) => (typeof name === 'string' ? s === name : name.test(s));
  return Array.from(root.querySelectorAll<HTMLElement>('fieldset, [role="group"], [role="region"], section[aria-labelledby], section[aria-label]')).find((el) => matches(named(el))) ?? null;
}
