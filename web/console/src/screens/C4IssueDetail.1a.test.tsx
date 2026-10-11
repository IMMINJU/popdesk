// 1a screens.md 2절 C-4 문의 상세: 댓글과 첨부 줄의 사람 표시에 사진을 쓴다(S1A-AC-60).
// 사진 주소는 /api/console/photos/{accountId}?v=<photoUpdatedAt>이고, 사진이 없는 사람은 청하지 않는다.
import { describe, expect, test } from 'vitest';
import { AGENT_PERSON, CUSTOMER_PERSON, attachment, comment, found, link, renderAt, screen, state, text } from '../__tests__/console';
import { PHOTO_AT, PHOTO_AT_NEW, photoOf, photosIn } from '../__tests__/console1a';

const commentWith = (body: string) =>
  found(() => screen.queryAllByRole('article').find((a) => text(a).includes(body) && a.querySelectorAll('article').length === 0), `댓글 "${body}"`);

describe('C-4 문의 상세 (1a)', () => {
  test('S1A-AC-60 C-4 사람 표시: 댓글과 첨부 줄에 그 사람의 사진을 쓰고, 사진이 없는 사람은 머리글자다', async () => {
    const customer = { ...CUSTOMER_PERSON, photoUpdatedAt: PHOTO_AT };
    const agent = { ...AGENT_PERSON, photoUpdatedAt: PHOTO_AT_NEW };
    state.comments = [
      comment({ id: 1, author: customer, content: '사진 있는 고객의 댓글' }),
      comment({ id: 2, author: agent, content: '사진 있는 상담원의 댓글' }),
      comment({ id: 3, author: { ...CUSTOMER_PERSON, accountId: 12, name: '박동료' }, content: '사진 없는 사람의 댓글' }),
    ];
    state.attachments = [
      attachment({ id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01', fileName: 'with-photo.txt', uploadedBy: agent }),
      attachment({ id: '0b5e9d42-6f1a-4c3b-8d27-1e9f4a6c2b10', fileName: 'no-photo.txt', uploadedBy: { ...CUSTOMER_PERSON, accountId: 12, name: '박동료' } }),
    ];
    renderAt('/issues/ACME-7');

    expect(photosIn(await commentWith('사진 있는 고객의 댓글'))).toEqual([photoOf(customer.accountId, PHOTO_AT)]);
    expect(photosIn(await commentWith('사진 있는 상담원의 댓글'))).toEqual([photoOf(agent.accountId, PHOTO_AT_NEW)]);
    expect(photosIn(await commentWith('사진 없는 사람의 댓글'))).toEqual([]);
    const withPhoto = (await link(/with-photo\.txt/)).closest('li');
    const noPhoto = (await link(/no-photo\.txt/)).closest('li');
    expect(withPhoto && photosIn(withPhoto), '첨부 줄의 올린 사람 사진').toEqual([photoOf(agent.accountId, PHOTO_AT_NEW)]);
    expect(noPhoto && photosIn(noPhoto)).toEqual([]);
  });
});
