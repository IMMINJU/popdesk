// 5번 묶음 screens.md 1~3절, C-4 문의 상세: 본문과 댓글의 이미지(S5-AC-50), 댓글 쓰기·댓글 고치기·문의 고치기의
// 편집기에서 올리기와 저장의 imageIds(S5-AC-54, 57, 58), 미리 보기의 주소(S5-AC-56), 첨부 목록의 "본문 이미지"와
// 이력의 첨부 줄(S5-AC-59). 편집기와 마크다운 보기 자체는 @popdesk/ui의 테스트가 본다.
import { fireEvent } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT_PERSON,
  CUSTOMER_PERSON,
  alertWith,
  attachment,
  button,
  callsTo,
  comment,
  eventually,
  expectNoAxeViolations,
  field,
  found,
  gate,
  hrefPath,
  isBlocked,
  on,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  text,
  within,
} from '../__tests__/console';
import { answered, rejected, settle } from '../__tests__/console1a';
import { issueIn, opener, s3, type UpdateIssueRequest } from '../__tests__/console3';
import {
  COMMENTS_7,
  EDIT,
  buttonIn,
  commentAction,
  commentById,
  editBox,
  editForm,
  writeBox,
  type CreateCommentRequest,
  type UpdateCommentRequest,
} from '../__tests__/console4';
import {
  IMAGES,
  ISSUE_7,
  MEMO_IMAGE,
  NEW_IMAGE,
  OLD_IMAGE,
  REPLY_IMAGE,
  acceptImage,
  attachmentHistory,
  attachmentPath,
  attachmentRow,
  deletedAttachment,
  imageButtonIn,
  imageFile,
  imageIdsOf,
  imageIn,
  imageSyntax,
  inlineAttachment,
  issueBody,
  pickImages,
  previewIn,
  srcPath,
  temporaryPath,
  uploadedImages,
} from '../__tests__/console5';

const URL_7 = '/issues/ACME-7';
const SAVE_COMMENT = '고친 댓글 저장';
const CANCEL_COMMENT = '고치기 취소';
const STALE_IMAGE = '본문의 이미지 가운데 올린 지 오래된 것이 있습니다. 이미지를 다시 올린 뒤 저장하세요';
const OLD_SYNTAX = imageSyntax('옛 화면', OLD_IMAGE);

type User = ReturnType<typeof renderAt>['user'];

const writeFormNow = () => screen.queryByRole('form', { name: '댓글 쓰기' });
const issueContentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
/** The form that edits the issue: the one holding the 내용 editor. */
const issueForm = () => screen.queryAllByLabelText<HTMLTextAreaElement>('내용', { selector: 'textarea' })[0]?.closest('form') ?? null;
const commentPatches = () => callsTo('PATCH', /\/comments\/\d+$/);

/** My own comment 21 on an open issue, holding an image attached before. */
function withMyComment() {
  state.issue = issueIn('IN_PROGRESS');
  state.comments = [comment({ id: 21, author: ADMIN_PERSON, content: `재현 화면입니다\n\n${OLD_SYNTAX}` })];
  state.attachments = [inlineAttachment({ commentId: 21, uploadedBy: ADMIN_PERSON })];
}

/** The issue ACME-7 with an image attached before in its content. */
function withIssueImage() {
  state.issue = issueIn('IN_PROGRESS', { content: `로그인 화면입니다\n\n${OLD_SYNTAX}`, version: 4 });
  state.attachments = [inlineAttachment()];
}

async function openCommentEdit(user: User) {
  await user.click(await commentAction('재현 화면입니다', EDIT));
  return editBox();
}

async function openIssueEdit(user: User) {
  await user.click(await button('고치기'));
  return issueContentBox();
}

/** Waits until `box` holds the markdown of the uploaded image. */
async function inserted(box: () => Promise<HTMLTextAreaElement>, name: string, id: string) {
  const el = await box();
  await eventually(() => {
    expect(el.value, '편집칸의 글').toContain(`${imageSyntax(name, id)}\n`);
  });
}

async function showPreview(user: User, scope: () => HTMLElement | null) {
  await user.click(
    await found(() => {
      const root = scope();
      return root && within(root).queryByRole('tab', { name: /미리 보기$/ });
    }, '탭 "미리 보기"'),
  );
}

describe('C-4 문의 상세: 본문 이미지', () => {
  test('S5-AC-50 C-4 그리기: 문의 본문, 공개 댓글, 내부 댓글의 ![설명](attachment:ID)가 그 문의의 첨부 주소에서 읽는 이미지가 되고 설명이 이름이다', async () => {
    state.issue = issueIn('IN_PROGRESS', { content: `로그인 화면입니다\n\n${OLD_SYNTAX}` });
    state.comments = [
      comment({ id: 31, author: AGENT_PERSON, content: `이렇게 해 보세요\n\n${imageSyntax('답변 그림', REPLY_IMAGE)}` }),
      comment({ id: 32, author: AGENT_PERSON, internal: true, content: `안에서만 봅니다\n\n${imageSyntax('메모 그림', MEMO_IMAGE)}` }),
    ];
    state.attachments = [
      inlineAttachment(),
      inlineAttachment({ id: REPLY_IMAGE, fileName: 'reply.png', commentId: 31, uploadedBy: AGENT_PERSON }),
      inlineAttachment({ id: MEMO_IMAGE, fileName: 'memo.png', commentId: 32, internal: true, uploadedBy: AGENT_PERSON }),
    ];
    renderAt(URL_7);
    await found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');

    expect(srcPath(await imageIn(issueBody, '옛 화면')), '문의 본문의 이미지').toBe(attachmentPath(OLD_IMAGE));
    expect(srcPath(await imageIn(() => commentById(31), '답변 그림')), '공개 댓글의 이미지').toBe(attachmentPath(REPLY_IMAGE));
    expect(srcPath(await imageIn(() => commentById(32), '메모 그림')), '내부 댓글의 이미지').toBe(attachmentPath(MEMO_IMAGE));
    expect(text(issueBody()), '본문에 남은 문법 글자').not.toContain('attachment:');
    expect(text(issueBody())).toContain('로그인 화면입니다');
  });

  test('S5-AC-50 C-4 axe: 이미지가 그려진 상세 화면에 접근성 위반이 없다', async () => {
    withIssueImage();
    renderAt(URL_7);
    await imageIn(issueBody, '옛 화면');

    await expectNoAxeViolations();
  });

  test.each([
    { kind: '공개 답변', pick: null, send: '고객에게 답변 보내기', internal: false },
    { kind: '내부 메모', pick: '내부 메모', send: '내부 메모 남기기', internal: true },
  ])('S5-AC-54 S5-AC-57 C-4 댓글 쓰기($kind): 편집기의 "이미지" 단추로 올리면 POST /api/console/images가 나가고 글에 문법이 들어간다. 보내면 imageIds에 그 ID가 간다', async ({ pick, send, internal }) => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await writeBox();
    if (pick) await user.click(await found(() => screen.queryByRole('radio', { name: pick }), `"${pick}" 라디오`));

    await pickImages(user, writeFormNow, imageFile('shot.png'));
    await sent('POST', IMAGES);
    expect(await uploadedImages()).toEqual(['shot.png']);
    await inserted(writeBox, 'shot.png', NEW_IMAGE);
    await user.click(await button(send));

    const [post] = await sent('POST', COMMENTS_7);
    const body = (await post?.body) as CreateCommentRequest;
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(body.internal ?? false).toBe(internal);
    expect(await imageIdsOf(post)).toEqual([NEW_IMAGE]);
  });

  test('S5-AC-54 C-4 댓글 쓰기, 올리는 동안: "이미지 올리는 중"과 파일 이름을 알리고 보내기 단추가 눌리지 않는다. 올라가면 풀린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('post', IMAGES, async () => {
      await release.opened;
      return acceptImage();
    });
    const { user } = renderAt(URL_7);
    await user.type(await writeBox(), '화면을 붙입니다');
    await pickImages(user, writeFormNow, imageFile('shot.png'));

    expect(text(await statusWith('이미지 올리는 중'))).toContain('shot.png');
    expect(isBlocked(await button('고객에게 답변 보내기')), '올리는 동안 보내기 단추').toBe(true);
    await user.click(await button('고객에게 답변 보내기'));
    await settle();
    expect(callsTo('POST', COMMENTS_7), '올리는 동안 누른 보내기').toHaveLength(0);

    release.open();
    await inserted(writeBox, 'shot.png', NEW_IMAGE);
    await eventually(() => {
      expect(isBlocked(screen.getByRole('button', { name: '고객에게 답변 보내기' })), '올라간 뒤 보내기 단추').toBe(false);
    });
  });

  test('S5-AC-54 C-4 댓글 쓰기, 올리는 중의 Ctrl+Enter: 편집칸에서 눌러도 저장 요청이 나가지 않고 쓰던 글이 남는다. 올라간 뒤에 누르면 나간다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('post', IMAGES, async () => {
      await release.opened;
      return acceptImage();
    });
    const { user } = renderAt(URL_7);
    const box = await writeBox();
    await user.type(box, '화면을 붙입니다');
    await pickImages(user, writeFormNow, imageFile('shot.png'));
    await statusWith('이미지 올리는 중');

    await user.click(box);
    await user.keyboard('{Control>}{Enter}{/Control}');
    await settle();
    expect(callsTo('POST', COMMENTS_7), '올리는 동안의 Ctrl+Enter').toHaveLength(0);
    expect((await writeBox()).value, '쓰던 글').toBe('화면을 붙입니다');

    release.open();
    await inserted(writeBox, 'shot.png', NEW_IMAGE);
    await eventually(() => {
      expect(isBlocked(screen.getByRole('button', { name: '고객에게 답변 보내기' })), '올라간 뒤 보내기 단추').toBe(false);
    });
    await user.click(await writeBox());
    await user.keyboard('{Control>}{Enter}{/Control}');

    const [post] = await sent('POST', COMMENTS_7);
    expect(await imageIdsOf(post)).toEqual([NEW_IMAGE]);
  });

  test.each([
    {
      editor: '내부 메모 쓰기',
      prepare: () => {
        state.issue = issueIn('IN_PROGRESS');
      },
      open: async (user: User) => {
        const box = await writeBox();
        await user.click(await found(() => screen.queryByRole('radio', { name: '내부 메모' }), '"내부 메모" 라디오'));
        return box;
      },
      scope: writeFormNow,
      save: '내부 메모 남기기',
    },
    { editor: '댓글 고치기', prepare: withMyComment, open: openCommentEdit, scope: editForm, save: SAVE_COMMENT },
    { editor: '문의 고치기', prepare: withIssueImage, open: openIssueEdit, scope: issueForm, save: '저장' },
  ])('S5-AC-54 C-4 $editor, 올리는 동안: "$save"가 막히고 올라가면 풀린다', async ({ prepare, open, scope, save }) => {
    prepare();
    const release = gate();
    on('post', IMAGES, async () => {
      await release.opened;
      return acceptImage();
    });
    const { user } = renderAt(URL_7);
    // A text that differs from the saved one: "저장" of an unchanged issue is blocked whether or not an upload runs.
    await user.type(await open(user), '더 적습니다');
    await eventually(() => {
      expect(isBlocked(within(scope() as HTMLElement).getByRole('button', { name: save })), `올리기 전 "${save}"`).toBe(false);
    });

    await pickImages(user, scope, imageFile('shot.png'));
    expect(text(await statusWith('이미지 올리는 중'))).toContain('shot.png');
    expect(isBlocked(await buttonIn(scope, save)), `올리는 동안 "${save}"`).toBe(true);

    release.open();
    await eventually(() => {
      expect(isBlocked(within(scope() as HTMLElement).getByRole('button', { name: save })), `올라간 뒤 "${save}"`).toBe(false);
    });
  });

  test('S5-AC-57 C-4 댓글 쓰기: 글에 이 편집에서 올리지 않은 attachment:ID(문의에 있던 이미지)가 있어도 imageIds는 여기서 올린 것 하나뿐이다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.attachments = [inlineAttachment()];
    const { user } = renderAt(URL_7);
    const box = await writeBox();
    await user.click(box);
    fireEvent.change(box, { target: { value: `${OLD_SYNTAX}\n` } });
    await pickImages(user, writeFormNow, imageFile('shot.png'));
    await inserted(writeBox, 'shot.png', NEW_IMAGE);
    await user.click(await button('고객에게 답변 보내기'));

    const [post] = await sent('POST', COMMENTS_7);
    const body = (await post?.body) as CreateCommentRequest;
    expect(body.content, '있던 이미지의 글은 그대로 간다').toContain(OLD_SYNTAX);
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(await imageIdsOf(post)).toEqual([NEW_IMAGE]);
  });

  test('S5-AC-58 C-4 댓글 쓰기, 저장이 400 TEMPORARY_IMAGE_INVALID: 쓴 글이 남고 "이미지를 다시 올린 뒤 저장하세요"를 알린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    on('post', COMMENTS_7, () => rejected('TEMPORARY_IMAGE_INVALID'));
    const { user } = renderAt(URL_7);
    await user.type(await writeBox(), '화면을 붙입니다');
    await pickImages(user, writeFormNow, imageFile('shot.png'));
    await inserted(writeBox, 'shot.png', NEW_IMAGE);
    await user.click(await button('고객에게 답변 보내기'));

    await alertWith(STALE_IMAGE);
    const kept = (await writeBox()).value;
    expect(kept).toContain('화면을 붙입니다');
    expect(kept).toContain(imageSyntax('shot.png', NEW_IMAGE));
  });

  test('S5-AC-57 C-4 댓글 고치기: 본문에 있던 이미지는 두고 새로 하나 올려 저장하면 imageIds는 새 것뿐이다', async () => {
    withMyComment();
    const { user } = renderAt(URL_7);
    await openCommentEdit(user);
    await pickImages(user, editForm, imageFile('shot.png'));
    await inserted(editBox, 'shot.png', NEW_IMAGE);
    await user.click(await buttonIn(editForm, SAVE_COMMENT));

    await eventually(() => {
      expect(commentPatches()).toHaveLength(1);
    });
    const patch = commentPatches()[0];
    const body = (await patch?.body) as UpdateCommentRequest;
    expect(body.content).toContain(OLD_SYNTAX);
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(await imageIdsOf(patch)).toEqual([NEW_IMAGE]);
  });

  test('S5-AC-57 C-4 문의 고치기: 본문에 있던 이미지는 두고 새로 하나 올려 저장하면 imageIds는 새 것뿐이고 version이 함께 간다', async () => {
    withIssueImage();
    const { user } = renderAt(URL_7);
    await openIssueEdit(user);
    await pickImages(user, issueForm, imageFile('shot.png'));
    await inserted(issueContentBox, 'shot.png', NEW_IMAGE);
    await user.click(await button('저장'));

    const [patch] = await sent('PATCH', ISSUE_7);
    const body = (await patch?.body) as UpdateIssueRequest;
    expect(body.content).toContain(OLD_SYNTAX);
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(body.version).toBe(4);
    expect(await imageIdsOf(patch)).toEqual([NEW_IMAGE]);
  });

  test('S5-AC-57 C-4 댓글 고치기, 저장한 뒤: 올린 이미지는 묶인 것이 된다. 다시 열어 글만 고쳐 저장하면 둘째 요청에 imageIds 칸이 없다', async () => {
    withMyComment();
    const { user } = renderAt(URL_7);
    await openCommentEdit(user);
    await pickImages(user, editForm, imageFile('shot.png'));
    await inserted(editBox, 'shot.png', NEW_IMAGE);
    await user.click(await buttonIn(editForm, SAVE_COMMENT));

    await eventually(() => {
      expect(commentPatches()).toHaveLength(1);
      expect(editForm(), '보기로 돌아감').toBeNull();
    });
    expect(await imageIdsOf(commentPatches()[0])).toEqual([NEW_IMAGE]);
    expect(srcPath(await imageIn(() => commentById(21), 'shot.png')), '저장한 댓글의 이미지').toBe(attachmentPath(NEW_IMAGE));

    const again = await openCommentEdit(user);
    expect(again.value).toContain(imageSyntax('shot.png', NEW_IMAGE));
    await user.type(again, '다시 봤습니다');
    await user.click(await buttonIn(editForm, SAVE_COMMENT));

    await eventually(() => {
      expect(commentPatches()).toHaveLength(2);
    });
    const second = commentPatches()[1];
    const body = (await second?.body) as UpdateCommentRequest;
    expect(body.content).toContain('다시 봤습니다');
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(await imageIdsOf(second), '이미 묶인 이미지뿐인 저장의 imageIds 칸').toBeUndefined();
  });

  test('S5-AC-57 C-4 문의 고치기, 저장한 뒤: 올린 이미지는 묶인 것이 된다. 다시 열어 글만 고쳐 저장하면 둘째 요청에 imageIds 칸이 없다', async () => {
    withIssueImage();
    const { user } = renderAt(URL_7);
    await openIssueEdit(user);
    await pickImages(user, issueForm, imageFile('shot.png'));
    await inserted(issueContentBox, 'shot.png', NEW_IMAGE);
    await user.click(await button('저장'));

    const [first] = await sent('PATCH', ISSUE_7);
    expect(await imageIdsOf(first)).toEqual([NEW_IMAGE]);
    // The page has read the saved issue again: only then does "고치기" open with the new version.
    await eventually(() => {
      expect(issueForm(), '보기로 돌아감').toBeNull();
    });
    expect(srcPath(await imageIn(issueBody, 'shot.png')), '저장한 본문의 이미지').toBe(attachmentPath(NEW_IMAGE));

    const again = await openIssueEdit(user);
    expect(again.value).toContain(imageSyntax('shot.png', NEW_IMAGE));
    await user.type(again, '다시 봤습니다');
    await user.click(await button('저장'));

    const patches = await sent('PATCH', ISSUE_7, 2);
    const body = (await patches[1]?.body) as UpdateIssueRequest;
    expect(body.content).toContain('다시 봤습니다');
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(body.version).toBe(5);
    expect(await imageIdsOf(patches[1]), '이미 묶인 이미지뿐인 저장의 imageIds 칸').toBeUndefined();
  });

  test('S5-AC-56 C-4 댓글 고치기의 미리 보기: 방금 올린 이미지는 임시 주소(/api/console/images/ID)로, 있던 이미지는 문의의 첨부 주소로 그린다', async () => {
    withMyComment();
    const { user } = renderAt(URL_7);
    await openCommentEdit(user);
    await pickImages(user, editForm, imageFile('shot.png'));
    await inserted(editBox, 'shot.png', NEW_IMAGE);
    await showPreview(user, editForm);

    const preview = () => previewIn(editForm);
    expect(srcPath(await imageIn(preview, 'shot.png')), '방금 올린 이미지').toBe(temporaryPath(NEW_IMAGE));
    expect(srcPath(await imageIn(preview, '옛 화면')), '있던 이미지').toBe(attachmentPath(OLD_IMAGE));
  });

  test('S5-AC-56 C-4 문의 고치기의 미리 보기: 방금 올린 이미지는 임시 주소로, 있던 이미지는 문의의 첨부 주소로 그린다', async () => {
    withIssueImage();
    const { user } = renderAt(URL_7);
    await openIssueEdit(user);
    await pickImages(user, issueForm, imageFile('shot.png'));
    await inserted(issueContentBox, 'shot.png', NEW_IMAGE);
    await showPreview(user, issueForm);

    const preview = () => previewIn(issueForm);
    expect(srcPath(await imageIn(preview, 'shot.png')), '방금 올린 이미지').toBe(temporaryPath(NEW_IMAGE));
    expect(srcPath(await imageIn(preview, '옛 화면')), '있던 이미지').toBe(attachmentPath(OLD_IMAGE));
  });

  test('S5-AC-54 C-4 댓글 고치기를 그만둔 뒤 온 올리기 답: 아무것도 바꾸지 않는다. 다시 고치기를 열면 원래 글이다', async () => {
    withMyComment();
    const release = gate();
    on('post', IMAGES, async () => {
      await release.opened;
      return acceptImage();
    });
    const { user } = renderAt(URL_7);
    const before = (await openCommentEdit(user)).value;
    await pickImages(user, editForm, imageFile('shot.png'));
    await statusWith('이미지 올리는 중');
    await user.click(await buttonIn(editForm, CANCEL_COMMENT));
    await eventually(() => {
      expect(editForm(), '고치기를 그만둠').toBeNull();
    });

    const done = answered('POST', IMAGES);
    release.open();
    await done;
    await settle();

    expect(editForm()).toBeNull();
    expect(commentPatches()).toHaveLength(0);
    expect(screen.queryAllByRole('alert').map((el) => text(el)).join(' ')).not.toContain('이미지');
    expect((await openCommentEdit(user)).value).toBe(before);
    expect(isBlocked(await buttonIn(editForm, SAVE_COMMENT)), `다시 연 편집기의 "${SAVE_COMMENT}"`).toBe(false);
    expect(screen.queryAllByRole('status').some((el) => text(el).includes('이미지 올리는 중')), '다시 연 편집기의 "올리는 중"').toBe(false);
  });

  test('S5-AC-59 C-4 첨부 목록: inline인 줄에만 "본문 이미지" 표시가 있다. 내려받기 링크와 "지우기"는 다른 첨부와 같다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.attachments = [inlineAttachment(), attachment({ id: MEMO_IMAGE, fileName: 'log.txt' })];
    renderAt(URL_7);

    const image = await found(() => attachmentRow('old.png'), '첨부 줄 old.png');
    const plain = await found(() => attachmentRow('log.txt'), '첨부 줄 log.txt');
    expect(text(image), '본문 이미지의 줄').toContain('본문 이미지');
    expect(text(plain), '보통 첨부의 줄').not.toContain('본문 이미지');
    expect(hrefPath(within(image).getByRole('link', { name: 'old.png' }))).toBe(attachmentPath(OLD_IMAGE));
    expect(hrefPath(within(plain).getByRole('link', { name: 'log.txt' }))).toBe(attachmentPath(MEMO_IMAGE));
    expect(within(image).queryByRole('button', { name: /지우기/ }), '본문 이미지의 "지우기"').not.toBeNull();
    expect(within(plain).queryByRole('button', { name: /지우기/ }), '보통 첨부의 "지우기"').not.toBeNull();
  });

  test.each([
    {
      what: '붙임',
      entry: () => attachmentHistory('attached', OLD_IMAGE),
      files: () => [attachment({ id: OLD_IMAGE, fileName: 'trace.log' })],
      says: ['이상담', '첨부를 붙임', 'trace.log'],
      not: ['첨부를 지움', '지워진 파일'],
    },
    {
      what: '고객이 본문 이미지를 붙임',
      entry: () => attachmentHistory('attached', OLD_IMAGE, { actor: CUSTOMER_PERSON }),
      files: () => [inlineAttachment()],
      says: ['김고객', '(고객)', '첨부를 붙임', 'old.png'],
      not: ['첨부를 지움', '지워진 파일'],
    },
    {
      what: '붙였지만 지금은 지워져 이름이 null',
      entry: () => attachmentHistory('attached', OLD_IMAGE),
      files: () => [deletedAttachment({ id: OLD_IMAGE })],
      says: ['이상담', '첨부를 붙임', '지워진 파일'],
      not: ['첨부를 지움'],
    },
    {
      what: '지움, 목록에 흔적이 있고 이름이 null',
      entry: () => attachmentHistory('deleted', OLD_IMAGE),
      files: () => [deletedAttachment({ id: OLD_IMAGE })],
      says: ['이상담', '첨부를 지움', '지워진 파일'],
      not: ['첨부를 붙임'],
    },
    {
      what: '지움, 목록에 없음',
      entry: () => attachmentHistory('deleted', OLD_IMAGE),
      files: () => [attachment({ id: MEMO_IMAGE, fileName: 'other.txt' })],
      says: ['이상담', '첨부를 지움', '지워진 파일'],
      not: ['첨부를 붙임', 'other.txt'],
    },
  ])('S5-AC-59 C-4 이력의 첨부 줄($what): $says', async ({ entry, files, says, not }) => {
    const at = '2026-10-09T01:02:03Z';
    state.issue = issueIn('IN_PROGRESS');
    state.attachments = files();
    s3.history = [{ ...entry(), createdAt: at }];
    const { user } = renderAt(URL_7);
    await user.click(await opener(/^이력/));

    const line = await found(() => screen.queryAllByRole('listitem').find((li) => /첨부를 (붙임|지움)/.test(text(li))), '이력의 첨부 줄');
    for (const part of says) expect(text(line), `이력 줄에 "${part}"`).toContain(part);
    for (const part of not) expect(text(line), `이력 줄에 없어야 할 "${part}"`).not.toContain(part);
    expect(text(line), '이력 줄에 첨부 ID').not.toContain(OLD_IMAGE);
    const times = Array.from(line.querySelectorAll('time')).map((t) => Date.parse(t.getAttribute('datetime') ?? ''));
    expect(times, '줄의 시각').toContain(Date.parse(at));
  });

  test('S5-AC-54 C-4 편집기 셋(댓글 쓰기, 댓글 고치기, 문의 고치기)에 "이미지" 단추가 있다', async () => {
    withMyComment();
    const { user } = renderAt(URL_7);
    await writeBox();
    await found(() => imageButtonIn(writeFormNow), '댓글 쓰기의 "이미지" 단추');

    await openCommentEdit(user);
    await found(() => imageButtonIn(editForm), '댓글 고치기의 "이미지" 단추');
    await user.click(await buttonIn(editForm, CANCEL_COMMENT));

    await openIssueEdit(user);
    await found(() => imageButtonIn(issueForm), '문의 고치기의 "이미지" 단추');
  });
});
