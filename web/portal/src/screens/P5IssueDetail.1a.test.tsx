// 1a screens.md 2절 P-5 문의 상세: 댓글과 첨부 줄의 사람 표시에 사진을 쓴다. 상담 쪽 사람은 사진 없이 "지원팀"이다(S1A-AC-60).
// 소속에서 빠진 고객의 읽기 전용 안내(S1A-AC-54)는 잠긴 P5IssueDetail.test.tsx가 이미 본다.
import { describe, expect, test } from 'vitest';
import {
  COLLEAGUE,
  ME_PERSON,
  SUPPORT_TEAM,
  attachment,
  comment,
  found,
  link,
  renderAt,
  screen,
  state,
  text,
} from '../__tests__/portal';
import { PHOTO_AT, PHOTO_AT_NEW, photoOf, photosIn, signInWith } from '../__tests__/portal1a';

const commentWith = (body: string) =>
  found(() => screen.queryAllByRole('article').find((a) => text(a).includes(body) && a.querySelectorAll('article').length === 0), `댓글 "${body}"`);

describe('P-5 문의 상세 (1a)', () => {
  test('S1A-AC-60 P-5 사람 표시: 고객의 댓글과 첨부 줄에는 그 사람의 사진, 상담 쪽은 사진 없이 "지원팀", 사진이 없는 사람은 머리글자', async () => {
    const me = { ...ME_PERSON, photoUpdatedAt: PHOTO_AT };
    const colleague = { ...COLLEAGUE, photoUpdatedAt: PHOTO_AT_NEW };
    signInWith({ photoUpdatedAt: PHOTO_AT });
    state.comments = [
      comment({ id: 1, author: me, content: '사진 있는 내 댓글' }),
      comment({ id: 2, author: colleague, content: '사진 있는 동료의 댓글' }),
      comment({ id: 3, author: SUPPORT_TEAM, content: '상담 쪽의 댓글' }),
      comment({ id: 4, author: COLLEAGUE, content: '사진 없는 동료의 댓글' }),
    ];
    state.attachments = [
      attachment({ id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01', fileName: 'with-photo.txt', uploadedBy: colleague }),
      attachment({ id: '0b5e9d42-6f1a-4c3b-8d27-1e9f4a6c2b10', fileName: 'support.pdf', uploadedBy: SUPPORT_TEAM }),
    ];
    renderAt('/issues/ACME-7');

    expect(photosIn(await commentWith('사진 있는 내 댓글'))).toEqual([photoOf(11, PHOTO_AT)]);
    expect(photosIn(await commentWith('사진 있는 동료의 댓글'))).toEqual([photoOf(12, PHOTO_AT_NEW)]);
    expect(photosIn(await commentWith('사진 없는 동료의 댓글'))).toEqual([]);
    const support = await commentWith('상담 쪽의 댓글');
    expect(photosIn(support), '상담 쪽은 사진 없음').toEqual([]);
    const withPhoto = (await link(/with-photo\.txt/)).closest('li');
    const supportFile = (await link(/support\.pdf/)).closest('li');
    expect(withPhoto && photosIn(withPhoto), '첨부 줄의 올린 사람 사진').toEqual([photoOf(12, PHOTO_AT_NEW)]);
    expect(supportFile && photosIn(supportFile), '상담 쪽이 올린 첨부').toEqual([]);
  });
});
