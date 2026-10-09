// Test support for the shared component tests (screens.md 5절).
//
// - Components are found by role and accessible name, never by class or data-pd.
// - Every check goes through `expect`, so a missing behavior fails as an assertion.
import axe from 'axe-core';
import { waitFor } from '@testing-library/react';
import { expect } from 'vitest';

/**
 * axe on `root`. jsdom has no layout, so the contrast rule is off. A component is
 * drawn alone, outside a page, so the page-level `region` rule (content inside
 * landmarks) is off too; the screen tests keep it.
 */
export async function expectNoAxeViolations(root: Element = document.body) {
  const result = await axe.run(root, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  const found = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(found).toEqual([]);
}

export function text(el: Element | null | undefined): string {
  return (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/** Text a screen reader would read: leaves out `aria-hidden` subtrees. */
export function spokenText(el: Element): string {
  const parts: string[] = [];
  const walk = (node: Node) => {
    if (node instanceof Element && node.getAttribute('aria-hidden') === 'true') return;
    if (node.nodeType === Node.TEXT_NODE) parts.push(node.textContent ?? '');
    node.childNodes.forEach(walk);
  };
  walk(el);
  return parts.join('').replace(/\s+/g, ' ').trim();
}

/** Texts joined to `el` by `aria-describedby`. */
export function describedText(el: Element): string {
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.map((id) => text(document.getElementById(id))).join(' ');
}

/** Waits until `query` returns an element and returns it. */
export async function found<T extends Element>(query: () => T | null | undefined, what: string): Promise<T> {
  let el: T | null | undefined;
  await waitFor(() => {
    el = query();
    expect(el ?? null, what).not.toBeNull();
  });
  return el as T;
}

/** The element `query` returns, failing through `expect` when there is none. */
export function must<T extends Element>(el: T | null | undefined, what: string): T {
  expect(el ?? null, what).not.toBeNull();
  return el as T;
}

/** Elements the Tab key reaches, in document order. */
export function tabbables(root: ParentNode = document.body): HTMLElement[] {
  const sel = 'a[href], button, input, select, textarea, [tabindex]';
  return Array.from(root.querySelectorAll<HTMLElement>(sel)).filter((el) => {
    if (el.getAttribute('tabindex') === '-1') return false;
    if ((el as HTMLButtonElement).disabled) return false;
    if (el instanceof HTMLInputElement && el.type === 'hidden') return false;
    return el.closest('[hidden], [inert]') === null;
  });
}

/** Blocked while saving: `aria-disabled` or `disabled`. */
export function isBlocked(el: Element): boolean {
  return el.getAttribute('aria-disabled') === 'true' || (el as HTMLButtonElement).disabled;
}

/** A file whose `size` says what the test needs without allocating it. */
export function file(name: string, size = 1024, type = 'text/plain'): File {
  const f = new File([name], name, { type });
  Object.defineProperty(f, 'size', { value: size });
  return f;
}

export const MB = 1024 * 1024;
