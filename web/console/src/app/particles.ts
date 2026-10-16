// Korean particles that depend on the word before them.

/** "로" after a vowel or ㄹ, "으로" after any other final consonant: 접수로, 처리 중으로. */
export function toward(word: string): string {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  if (code < 0 || code > 11171) return `${word}(으)로`;
  const final = code % 28;
  return `${word}${final === 0 || final === 8 ? '로' : '으로'}`;
}

/** "이" after a final consonant, "가" after a vowel: 최관리가, 김민준이. "이(가)" after a letter that is not Hangul. */
export function subject(word: string): string {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  if (code < 0 || code > 11171) return `${word}이(가)`;
  return `${word}${code % 28 === 0 ? '가' : '이'}`;
}
