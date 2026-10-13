// S5-AC-57: 저장은 이 편집에서 올린 이미지 가운데 본문에 아직 `(attachment:{id})`로 남은 것의 ID만 보낸다.
// 본문을 읽지 않고 시그니처, 주석, 수용 기준(S5-AC-57, S5-AC-25), screens.md 5절 7·9로 썼다.
import { describe, expect, it } from 'vitest';
import { imageIdsToSend } from './bodyImages';

const A = '0b0e7a52-3c1d-4f6a-9a51-6d2f0c1e8a01';
const B = '5f3c9d10-77aa-4e0b-8c2e-1a9b4d7e6f02';
const C = 'c2d4e6f8-1357-4bdf-a024-68ace0246803';
const OTHER = '9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c04';
const image = (id: string, alt = 'shot.png') => `![${alt}](attachment:${id})`;

describe('imageIdsToSend', () => {
  it.each<[string, string[], string]>([
    ['올린 것이 없고 글도 비었다', [], ''],
    ['올린 것이 없고 글만 있다', [], '글만 고쳤습니다'],
    ['올렸지만 글이 비었다', [A], ''],
    ['올렸지만 글이 빈칸과 줄 바꿈뿐이다', [A, B], ' \n\t \n'],
    ['올렸지만 글에 이미지가 없다', [A, B], '이미지를 뺐습니다'],
    ['올린 것을 글에서 다 지웠다', [A, B], `앞\n\n뒤 ${image(OTHER)}`],
  ])('S5-AC-57 보낼 것이 없으면 빈 목록이다: %s', (_, uploaded, content) => {
    expect(imageIdsToSend(uploaded, content)).toEqual([]);
  });

  it.each<[string, string[], string, string[]]>([
    ['하나를 올리고 글에 있다', [A], image(A), [A]],
    ['글 가운데에 있다', [A], `앞 글\n\n${image(A)}\n뒤 글`, [A]],
    ['설명이 비었다', [A], image(A, ''), [A]],
    ['셋을 올리고 셋 다 있다', [A, B, C], `${image(A)}\n${image(B)}\n${image(C)}`, [A, B, C]],
    ['한 줄에 붙어 있다', [A, B], `${image(A)}${image(B)}`, [A, B]],
    ['둘을 올리고 뒤의 것을 지웠다', [A, B], `${image(A)}\n지웠습니다`, [A]],
    ['둘을 올리고 앞의 것을 지웠다', [A, B], `지웠습니다\n${image(B)}`, [B]],
    ['셋을 올리고 가운데 것을 지웠다', [A, B, C], `${image(A)}\n${image(C)}`, [A, C]],
  ])('S5-AC-57 올린 것 가운데 글에 남은 것만 보낸다: %s', (_, uploaded, content, want) => {
    expect(imageIdsToSend(uploaded, content)).toEqual(want);
  });

  it.each<[string, string[], string, string[]]>([
    ['글에서는 거꾸로 적혔다', [A, B, C], `${image(C)}\n${image(B)}\n${image(A)}`, [A, B, C]],
    ['글에서 자리를 바꿨다', [B, A], `${image(A)}\n${image(B)}`, [B, A]],
    ['하나를 지우고 남은 둘이 거꾸로다', [C, A, B], `${image(B)}\n${image(C)}`, [C, B]],
  ])('S5-AC-57 차례는 글이 아니라 올린 차례다: %s', (_, uploaded, content, want) => {
    expect(imageIdsToSend(uploaded, content)).toEqual(want);
  });

  it.each<[string, string[], string, string[]]>([
    ['글에 같은 이미지가 두 번 있다', [A], `${image(A)}\n${image(A, '다시')}`, [A]],
    ['올린 목록에 같은 ID가 두 번 있다', [A, A], image(A), [A]],
    ['둘 다 두 번이다', [A, B, A, B], `${image(B)}${image(A)}${image(B)}${image(A)}`, [A, B]],
    ['겹친 ID 사이에 다른 것이 있다', [B, A, B], `${image(A)}\n${image(B)}`, [B, A]],
  ])('S5-AC-57 같은 ID는 한 번만 보낸다: %s', (_, uploaded, content, want) => {
    expect(imageIdsToSend(uploaded, content)).toEqual(want);
  });

  it.each<[string, string[], string, string[]]>([
    ['올린 것이 없고 글에 있던 이미지만 있다', [], image(OTHER), []],
    ['있던 이미지와 새로 올린 것이 함께 있다', [A], `${image(OTHER)}\n${image(A)}`, [A]],
    ['새로 올린 것을 지우고 있던 이미지만 남았다', [A], image(OTHER), []],
    ['다른 문의에서 복사한 글의 ID가 여럿이다', [B], `${image(OTHER)}${image(C)}${image(B)}${image(A)}`, [B]],
  ])('S5-AC-57 S5-AC-25 이 편집에서 올리지 않은 ID는 글에 있어도 보내지 않는다: %s', (_, uploaded, content, want) => {
    expect(imageIdsToSend(uploaded, content)).toEqual(want);
  });

  it.each<[string, string]>([
    ['ID만 적혔다', A],
    ['괄호 없이 attachment:ID다', `attachment:${A}`],
    ['닫는 괄호가 없다', `![x](attachment:${A}`],
    ['여는 괄호가 없다', `![x]attachment:${A})`],
    ['ID 뒤에 글자가 더 붙었다', `![x](attachment:${A}0)`],
    ['ID 앞에 글자가 더 붙었다', `![x](attachment:0${A})`],
    ['ID의 앞부분만 있다', `![x](attachment:${A.slice(0, -1)})`],
    ['대문자 ID다', `![x](attachment:${A.toUpperCase()})`],
    ['ATTACHMENT로 적혔다', `![x](ATTACHMENT:${A})`],
    ['괄호 안에 빈칸이 있다', `![x]( attachment:${A} )`],
    ['쌍점 뒤에 빈칸이 있다', `![x](attachment: ${A})`],
    ['제목이 붙었다', `![x](attachment:${A} "제목")`],
    ['ID 가운데에서 줄이 바뀐다', `![x](attachment:${A.slice(0, 18)}\n${A.slice(18)})`],
    ['임시 경로의 주소다', `![x](/api/portal/images/${A})`],
  ])('S5-AC-57 글이 (attachment:ID)를 그대로 갖고 있지 않으면 보내지 않는다: %s', (_, content) => {
    expect(imageIdsToSend([A], content)).toEqual([]);
  });

  it('S5-AC-57 대소문자만 다른 두 ID는 다른 ID다', () => {
    const upper = A.toUpperCase();

    expect(imageIdsToSend([A, upper], image(A))).toEqual([A]);
    expect(imageIdsToSend([A, upper], image(upper))).toEqual([upper]);
    expect(imageIdsToSend([A, upper], `${image(upper)}${image(A)}`)).toEqual([A, upper]);
  });

  it('S5-AC-57 한 ID가 다른 ID의 앞부분이어도 글에 있는 것만 보낸다', () => {
    expect(imageIdsToSend(['12', '123'], image('123'))).toEqual(['123']);
    expect(imageIdsToSend(['12', '123'], image('12'))).toEqual(['12']);
    expect(imageIdsToSend(['23', '123'], image('123'))).toEqual(['123']);
  });

  it.each<[string, string, string]>([
    ['점', 'a.c', 'abc'],
    ['별', 'a*', 'aaa'],
    ['더하기', 'a+', 'aa'],
    ['대괄호', '[ab]', 'a'],
    ['세로줄', 'a|b', 'a'],
    ['역빗금', '\\d', '7'],
    ['물음표', 'ab?', 'a'],
    ['달러', 'a$', 'a'],
  ])('S5-AC-57 ID는 어떤 글자든 그 글자 그대로 견준다: %s', (_, id, lookalike) => {
    expect(imageIdsToSend([id], image(lookalike))).toEqual([]);
    expect(imageIdsToSend([id], image(id))).toEqual([id]);
  });

  it('S5-AC-57 만 자가 넘는 글의 맨 끝에 있어도 찾는다', () => {
    const long = '가나다라마바사아자차\n'.repeat(1000);

    expect(long.length).toBeGreaterThanOrEqual(10_000);
    expect(imageIdsToSend([A, B], `${long}${image(B)}`)).toEqual([B]);
    expect(imageIdsToSend([A, B], `${image(A)}${long}`)).toEqual([A]);
    expect(imageIdsToSend([A, B], long)).toEqual([]);
  });

  it('S5-AC-57 받은 목록을 바꾸지 않고, 같은 것을 다시 물으면 같은 답이다', () => {
    const uploaded = Object.freeze([B, A, B, C]);
    const content = `${image(A)}\n${image(B)}`;

    const first = imageIdsToSend(uploaded, content);
    const second = imageIdsToSend(uploaded, content);

    expect(first).toEqual([B, A]);
    expect(second).toEqual(first);
    expect(uploaded).toEqual([B, A, B, C]);
  });

  it('S5-AC-57 앞에 물은 것이 다음 답에 남지 않는다', () => {
    expect(imageIdsToSend([A, B], `${image(A)}${image(B)}`)).toEqual([A, B]);
    expect(imageIdsToSend([A, B], image(B))).toEqual([B]);
    expect(imageIdsToSend([A, B], '')).toEqual([]);
    expect(imageIdsToSend([A, B], image(A))).toEqual([A]);
  });
});
