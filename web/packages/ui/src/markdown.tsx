// A small Markdown reader that builds React elements from an allow list
// (COM-6). It never turns source text into HTML: raw HTML in the source stays
// text, and only http, https and mailto links become links.
//
// Blocks: paragraphs (a line break is a <br>), headings, fenced and indented
// code, quotes, bullet and numbered lists, rules.
// Inline: code spans, **strong**, *em*, ~~del~~, [links](url), <autolinks>,
// bare http(s) URLs, backslash escapes.
import { Fragment, type ReactNode } from 'react';

const SAFE_URL = /^(https?:\/\/|mailto:)/i;

function safeHref(url: string): string | null {
  const trimmed = url.trim();
  return SAFE_URL.test(trimmed) ? trimmed : null;
}

function link(href: string, children: ReactNode, key: string) {
  return (
    <a key={key} href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  );
}

/** Finds the `)` that closes the URL started at `from`, allowing balanced parentheses inside. */
function closingParen(text: string, from: number): number {
  let depth = 0;
  for (let i = from; i < text.length; i += 1) {
    const c = text[i];
    if (c === '\\') i += 1;
    else if (c === '(') depth += 1;
    else if (c === ')') {
      if (depth === 0) return i;
      depth -= 1;
    } else if (c === '\n') return -1;
  }
  return -1;
}

/** Finds the `]` that closes the link text started at `from`. */
function closingBracket(text: string, from: number): number {
  let depth = 0;
  for (let i = from; i < text.length; i += 1) {
    const c = text[i];
    if (c === '\\') i += 1;
    else if (c === '[') depth += 1;
    else if (c === ']') {
      if (depth === 0) return i;
      depth -= 1;
    }
  }
  return -1;
}

const PUNCT = /[!-/:-@[-`{-~]/;
const WORD = /[\p{L}\p{N}]/u;

function inline(text: string, key: string, inLink = false): ReactNode[] {
  const out: ReactNode[] = [];
  let plain = '';
  let n = 0;
  const flush = () => {
    if (plain === '') return;
    // Line breaks inside a paragraph are kept as <br>.
    const lines = plain.split('\n');
    lines.forEach((line, i) => {
      if (i > 0) out.push(<br key={`${key}-br${String(n++)}`} />);
      if (line !== '') out.push(line);
    });
    plain = '';
  };
  const push = (node: ReactNode) => {
    flush();
    out.push(node);
  };
  const k = () => `${key}-${String(n++)}`;

  let i = 0;
  while (i < text.length) {
    const c = text[i] ?? '';
    const rest = text.slice(i);

    if (c === '\\' && i + 1 < text.length && PUNCT.test(text[i + 1] ?? '')) {
      plain += text[i + 1] ?? '';
      i += 2;
      continue;
    }

    if (c === '`') {
      const run = /^`+/.exec(rest)?.[0] ?? '`';
      const end = text.indexOf(run, i + run.length);
      if (end > 0) {
        push(<code key={k()}>{text.slice(i + run.length, end).trim()}</code>);
        i = end + run.length;
        continue;
      }
      plain += run;
      i += run.length;
      continue;
    }

    if (c === '[' && !inLink) {
      const close = closingBracket(text, i + 1);
      if (close > 0 && text[close + 1] === '(') {
        const end = closingParen(text, close + 2);
        if (end > 0) {
          const label = text.slice(i + 1, close);
          const href = safeHref(text.slice(close + 2, end).split(/\s+"/)[0] ?? '');
          const children = inline(label, k(), true);
          push(href ? link(href, children, k()) : <Fragment key={k()}>{children}</Fragment>);
          i = end + 1;
          continue;
        }
      }
    }

    if (c === '<') {
      const auto = /^<((?:https?:\/\/|mailto:)[^\s<>]+)>/i.exec(rest);
      if (auto?.[1] && !inLink) {
        push(link(auto[1], auto[1], k()));
        i += auto[0].length;
        continue;
      }
    }

    if (!inLink && (c === 'h' || c === 'H') && !WORD.test(text[i - 1] ?? '')) {
      const bare = /^https?:\/\/[^\s<]+/i.exec(rest);
      if (bare) {
        const url = bare[0].replace(/[.,;:!?)\]'"]+$/, '');
        push(link(url, url, k()));
        i += url.length;
        continue;
      }
    }

    const pair = /^(\*\*|__|~~)/.exec(rest)?.[0];
    if (pair) {
      const end = text.indexOf(pair, i + 2);
      if (end > i + 2 && text[i + 2] !== ' ') {
        const inner = inline(text.slice(i + 2, end), k(), inLink);
        push(pair === '~~' ? <del key={k()}>{inner}</del> : <strong key={k()}>{inner}</strong>);
        i = end + 2;
        continue;
      }
    }

    if ((c === '*' || c === '_') && text[i + 1] !== ' ' && text[i + 1] !== c) {
      // `_` only opens outside a word, so snake_case stays as it is.
      const opens = c === '*' || !WORD.test(text[i - 1] ?? '');
      let end = opens ? text.indexOf(c, i + 1) : -1;
      while (end > 0 && (text[end - 1] === ' ' || (c === '_' && WORD.test(text[end + 1] ?? '')))) end = text.indexOf(c, end + 1);
      if (end > i + 1) {
        push(<em key={k()}>{inline(text.slice(i + 1, end), k(), inLink)}</em>);
        i = end + 1;
        continue;
      }
    }

    plain += c;
    i += 1;
  }
  flush();
  return out;
}

const FENCE = /^ {0,3}(```+|~~~+)\s*([\w+-]*)\s*$/;
const HEADING = /^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE = /^ {0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE = /^ {0,3}> ?/;
const BULLET = /^ {0,3}[-*+]\s+/;
const ORDERED = /^ {0,3}(\d{1,9})[.)]\s+/;

function isBlockStart(line: string): boolean {
  return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || QUOTE.test(line) || BULLET.test(line) || ORDERED.test(line);
}

/**
 * Headings start at h3 so they sit under the screen's h1 and section h2s
 * (screens.md P-5 landmarks).
 */
function headingTag(level: number): 'h3' | 'h4' | 'h5' | 'h6' {
  const shifted = Math.min(level + 2, 6);
  return `h${String(shifted)}` as 'h3' | 'h4' | 'h5' | 'h6';
}

function blocks(lines: string[], key: string): ReactNode[] {
  const out: ReactNode[] = [];
  let i = 0;
  let n = 0;
  const k = () => `${key}-${String(n++)}`;

  while (i < lines.length) {
    const line = lines[i] ?? '';

    if (line.trim() === '') {
      i += 1;
      continue;
    }

    const fence = FENCE.exec(line);
    if (fence) {
      const marker = fence[1] ?? '```';
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !(lines[i] ?? '').trim().startsWith(marker)) {
        body.push(lines[i] ?? '');
        i += 1;
      }
      i += 1;
      out.push(
        <pre key={k()} tabIndex={0}>
          <code data-lang={fence[2] || undefined}>{body.join('\n')}</code>
        </pre>,
      );
      continue;
    }

    if (/^( {4}|\t)/.test(line)) {
      const body: string[] = [];
      while (i < lines.length && (/^( {4}|\t)/.test(lines[i] ?? '') || (lines[i] ?? '').trim() === '')) {
        body.push((lines[i] ?? '').replace(/^( {4}|\t)/, ''));
        i += 1;
      }
      while (body.length > 0 && (body[body.length - 1] ?? '').trim() === '') body.pop();
      out.push(
        <pre key={k()} tabIndex={0}>
          <code>{body.join('\n')}</code>
        </pre>,
      );
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      const Tag = headingTag((heading[1] ?? '#').length);
      const id = k();
      out.push(<Tag key={id}>{inline(heading[2] ?? '', id)}</Tag>);
      i += 1;
      continue;
    }

    if (RULE.test(line)) {
      out.push(<hr key={k()} />);
      i += 1;
      continue;
    }

    if (QUOTE.test(line)) {
      const body: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i] ?? '')) {
        body.push((lines[i] ?? '').replace(QUOTE, ''));
        i += 1;
      }
      const id = k();
      out.push(<blockquote key={id}>{blocks(body, id)}</blockquote>);
      continue;
    }

    const ordered = ORDERED.exec(line);
    if (BULLET.test(line) || ordered) {
      const marker = ordered ? ORDERED : BULLET;
      const items: string[][] = [];
      while (i < lines.length) {
        const current = lines[i] ?? '';
        if (marker.test(current)) {
          items.push([current.replace(marker, '')]);
        } else if (current.trim() !== '' && /^\s{2,}/.test(current) && items.length > 0) {
          items[items.length - 1]?.push(current.trim());
        } else {
          break;
        }
        i += 1;
      }
      const id = k();
      const lis = items.map((item, j) => <li key={`${id}-${String(j)}`}>{inline(item.join('\n'), `${id}-${String(j)}`)}</li>);
      const start = ordered ? Number(ordered[1]) : 1;
      out.push(
        ordered ? (
          <ol key={id} start={start === 1 ? undefined : start}>
            {lis}
          </ol>
        ) : (
          <ul key={id}>{lis}</ul>
        ),
      );
      continue;
    }

    const paragraph: string[] = [];
    while (i < lines.length && (lines[i] ?? '').trim() !== '' && (paragraph.length === 0 || !isBlockStart(lines[i] ?? ''))) {
      paragraph.push(lines[i] ?? '');
      i += 1;
    }
    const id = k();
    out.push(<p key={id}>{inline(paragraph.join('\n'), id)}</p>);
  }
  return out;
}

export function renderMarkdown(source: string): ReactNode[] {
  return blocks(source.replace(/\r\n?/g, '\n').split('\n'), 'md');
}
