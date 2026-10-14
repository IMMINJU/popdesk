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

/** Waits a frame twice, so a layout that changes after the first paint has settled. */
export async function settled(): Promise<void> {
  await nextFrame();
  await nextFrame();
}

/** Text of an element, spaces folded. */
export function textOf(el: Element | null | undefined): string {
  return (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/** The `dd` after the `dt` whose text is `term`, inside `root`. */
export function termValue(root: ParentNode, term: string): HTMLElement | null {
  const dt = Array.from(root.querySelectorAll('dt')).find((t) => textOf(t) === term);
  return dt?.nextElementSibling instanceof HTMLElement ? dt.nextElementSibling : null;
}

/** A button whose text or `aria-label` is `name`, inside `root`. */
export function buttonNamed(root: ParentNode, name: string): HTMLButtonElement | null {
  return Array.from(root.querySelectorAll('button')).find((b) => (b.getAttribute('aria-label') ?? textOf(b)) === name) ?? null;
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
