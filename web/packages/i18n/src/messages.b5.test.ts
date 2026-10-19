// b5 묶음 B5-AC-08: 두 언어의 표가 맞물린다. 키가 몇 개든(지금 것이든 옮겨 온 것이든) 모든 키를 본다.
// 같은 키 집합은 타입이 막지만(en.ts), 값의 자리 표시와 글자는 타입이 보지 않는다.
import { describe, expect, it } from 'vitest';
import { messages, type MessageKey } from './index';

const KEYS = Object.keys(messages.ko) as MessageKey[];
const HANGUL = /[가-힣ㄱ-ㅎㅏ-ㅣ]/;

/** The `{name}` places of a text, sorted, one entry per place. */
const places = (said: string): string[] => Array.from(said.matchAll(/\{(\w+)\}/g), (m) => m[1] ?? '').sort();

describe('@popdesk/i18n 두 언어 (b5 묶음)', () => {
  it('B5-AC-08 키 집합: en은 ko와 같은 키를 모두 가지고 더 가지지 않는다', () => {
    expect(KEYS.length).toBeGreaterThan(150);
    expect(Object.keys(messages.en).sort()).toEqual([...KEYS].sort());
  });

  it('B5-AC-08 자리 표시: 모든 키에서 {name}의 이름과 수가 두 언어에서 같다', () => {
    const differ = KEYS.filter((key) => places(messages.ko[key]).join(',') !== places(messages.en[key]).join(',')).map(
      (key) => `${key}: ko {${places(messages.ko[key]).join(',')}} en {${places(messages.en[key]).join(',')}}`,
    );
    expect(differ).toEqual([]);
  });

  it('B5-AC-08 en 값에 한글이 없다', () => {
    const korean = KEYS.filter((key) => HANGUL.test(messages.en[key])).map((key) => `${key}: ${messages.en[key]}`);
    expect(korean).toEqual([]);
  });

  it('B5-AC-08 ko 값과 en 값은 비지 않는다', () => {
    const empty = KEYS.filter((key) => messages.ko[key].trim() === '' || messages.en[key].trim() === '');
    expect(empty).toEqual([]);
  });
});
