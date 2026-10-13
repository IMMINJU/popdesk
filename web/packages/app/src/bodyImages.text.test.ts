// S5-AC-56: 이미지가 올라가지 않은 까닭의 글. 이미지에만 있는 두 코드는 자기 문구이고, 나머지는 첨부와 같은 말이다.
// 5번 묶음 검증(C-10)의 변이 테스트에서 이 패키지의 테스트가 닿지 않던 함수다.
import { describe, expect, it, test } from 'vitest';
import { uploadFailureText } from './attachments';
import { IMAGE_TEXT, imageFailureText } from './bodyImages';
import type { Failure } from './result';

const rejected = (code: string): Failure => ({ kind: 'rejected', status: 400, code, message: 'x' });

describe('imageFailureText', () => {
  test('S5-AC-56 imageFailureText: FILE_NOT_IMAGE는 이미지 형식 문구다', () => {
    expect(imageFailureText(rejected('FILE_NOT_IMAGE'))).toBe(IMAGE_TEXT.notImage);
  });

  test('S5-AC-56 imageFailureText: TEMPORARY_IMAGE_LIMIT_EXCEEDED는 저장하지 않은 이미지가 많다는 문구다', () => {
    expect(imageFailureText(rejected('TEMPORARY_IMAGE_LIMIT_EXCEEDED'))).toBe(IMAGE_TEXT.tooMany);
  });

  test('S5-AC-56 imageFailureText: 두 문구는 서로 다르고 비어 있지 않다', () => {
    expect(IMAGE_TEXT.notImage).not.toBe('');
    expect(IMAGE_TEXT.tooMany).not.toBe('');
    expect(IMAGE_TEXT.notImage).not.toBe(IMAGE_TEXT.tooMany);
  });

  it.each<[string, Failure]>([
    ['다른 코드의 거절', rejected('FILE_TOO_LARGE')],
    ['빈 코드의 거절', rejected('')],
    ['코드가 FILE_NOT_IMAGE인 403', { kind: 'forbidden', code: 'FILE_NOT_IMAGE' }],
    ['코드가 TEMPORARY_IMAGE_LIMIT_EXCEEDED인 403', { kind: 'forbidden', code: 'TEMPORARY_IMAGE_LIMIT_EXCEEDED' }],
    ['앞단의 크기 상한', { kind: 'tooLarge' }],
    ['잦은 요청', { kind: 'rateLimited', seconds: 30 }],
    ['서버 오류', { kind: 'server' }],
    ['연결 끊김', { kind: 'network' }],
  ])('S5-AC-56 imageFailureText: %s은 첨부와 같은 말이고 이미지 문구가 아니다', (_what, failure) => {
    const said = imageFailureText(failure);

    expect(said).toBe(uploadFailureText(failure));
    expect([IMAGE_TEXT.notImage, IMAGE_TEXT.tooMany]).not.toContain(said);
  });
});
