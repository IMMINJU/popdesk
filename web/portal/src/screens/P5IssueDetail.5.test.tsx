// 5번 묶음 screens.md 1~3절, P-5 문의 상세: 본문과 댓글의 이미지(S5-AC-50, 52), 댓글 쓰기·댓글 고치기·문의 고치기의
// 편집기에서 올리기와 저장의 imageIds(S5-AC-54, 57, 58), 미리 보기의 주소(S5-AC-56), 첨부 목록의 "본문 이미지"(S5-AC-59),
// 포털 화면의 이미지 주소에 /api/console이 없다(S5-AC-63). 편집기와 마크다운 보기 자체는 @popdesk/ui의 테스트가 본다.
import { fireEvent } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ME_PERSON,
  SUPPORT_TEAM,
  alertWith,
  attachment,
  button,
  callsTo,
  comment,
  dialog,
  eventually,
  expectNoAxeViolations,
  field,
  found,
  gate,
  heading1,
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
} from '../__tests__/portal';
import { answered, rejected, route, settle } from '../__tests__/portal1a';
import { said } from '../__tests__/portal2';
import { issueIn, type UpdateIssueRequest } from '../__tests__/portal3';
import { COMMENTS_7, EDIT, buttonIn, commentAction, commentById, editBox, editForm, writeBox, type UpdateCommentRequest } from '../__tests__/portal4';
import {
  ATTACHMENTS_7,
  IMAGES,
  ISSUE_7,
  NEW_IMAGE,
  OLD_IMAGE,
  REPLY_IMAGE,
  acceptImage,
  attachmentPath,
  attachmentRow,
  imageFile,
  imageIdsOf,
  imageIn,
  imageSyntax,
  inlineAttachment,
  issueBody,
  pickImages,
  srcPath,
  temporaryPath,
  uploadedImages,
} from '../__tests__/portal5';

const URL_7 = '/issues/ACME-7';
const SEND = '보내기';
const SAVE_COMMENT = '고친 댓글 저장';
const CANCEL_COMMENT = '고치기 취소';
const UNREADABLE = '이미지를 볼 수 없습니다';
const STALE_IMAGE = '본문의 이미지 가운데 올린 지 오래된 것이 있습니다. 이미지를 다시 올린 뒤 저장하세요';
const OLD_SYNTAX = imageSyntax('옛 화면', OLD_IMAGE);

type User = ReturnType<typeof renderAt>['user'];
type Scope = () => HTMLElement | null;

const writeFormNow = () => screen.queryByRole('form', { name: '댓글 쓰기' });
const issueForm = () => screen.queryByRole('form', { name: '문의 고치기' });
const issueContentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
const commentPatches = () => callsTo('PATCH', /\/comments\/\d+$/);

/** My own comment 5 on my open issue, holding an image attached before. */
function withMyComment() {
  state.issue = issueIn('IN_PROGRESS');
  state.comments = [comment({ id: 5, author: ME_PERSON, content: `재현 화면입니다\n\n${OLD_SYNTAX}` })];
  state.attachments = [inlineAttachment({ commentId: 5 })];
}

/** My issue ACME-7 with an image attached before in its content. */
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

/** Waits until the page has read the saved issue again and shows `part` in its content: only then does "고치기" open with the new version. */
async function savedBodyWith(part: string) {
  await eventually(() => {
    expect(issueForm(), '보기로 돌아감').toBeNull();
    expect(text(issueBody()), '다시 읽은 문의 본문').toContain(part);
  });
}

/** Waits until `box` holds the markdown of the uploaded image. */
async function inserted(box: () => Promise<HTMLTextAreaElement>, name: string, id: string) {
  const el = await box();
  await eventually(() => {
    expect(el.value, '편집칸의 글').toContain(`${imageSyntax(name, id)}\n`);
  });
}

/** Presses the preview tab of the editor inside `scope` ("미리 보기", or "고친 내용 미리 보기" of a comment being edited) and returns its panel. */
async function showPreview(user: User, scope: Scope): Promise<Scope> {
  await user.click(
    await found(() => {
      const root = scope();
      return root && within(root).queryByRole('tab', { name: /미리 보기$/ });
    }, '탭 "미리 보기"'),
  );
  return () => {
    const root = scope();
    return root && within(root).queryByRole('tabpanel', { name: /미리 보기$/ });
  };
}

/** The addresses of every picture on the page that is not a profile photo. */
function contentImagePaths(): string[] {
  return Array.from(document.querySelectorAll('img'))
    .map((img) => srcPath(img))
    .filter((path) => !path.includes('/photos/'));
}

/** The three editors of P-5 that save a text: how to open each, where it is, and its save. */
const EDITORS = [
  {
    editor: '댓글 쓰기',
    prepare: () => {
      state.issue = issueIn('IN_PROGRESS');
      state.attachments = [inlineAttachment()];
    },
    open: async (user: User) => {
      const box = await writeBox();
      await user.click(box);
      // A draft that already points at an image of this issue.
      fireEvent.change(box, { target: { value: `${OLD_SYNTAX}\n` } });
      return box;
    },
    scope: writeFormNow,
    box: writeBox,
    save: SEND,
    method: 'POST',
    path: COMMENTS_7 as string | RegExp,
    route: COMMENTS_7,
  },
  {
    editor: '댓글 고치기',
    prepare: withMyComment,
    open: openCommentEdit,
    scope: editForm,
    box: editBox,
    save: SAVE_COMMENT,
    method: 'PATCH',
    path: /\/comments\/5$/ as string | RegExp,
    route: `${COMMENTS_7}/:id`,
  },
  {
    editor: '문의 고치기',
    prepare: withIssueImage,
    open: openIssueEdit,
    scope: issueForm,
    box: issueContentBox,
    save: '저장',
    method: 'PATCH',
    path: ISSUE_7 as string | RegExp,
    route: ISSUE_7,
  },
];

describe('P-5 문의 상세: 본문 이미지', () => {
  test('S5-AC-50 S5-AC-63 P-5 그리기: 문의 본문과 댓글의 ![설명](attachment:ID)가 그 문의의 첨부 주소(/api/portal/issues/ACME-7/attachments/ID)에서 읽는 이미지가 되고 설명이 이름이다', async () => {
    state.issue = issueIn('IN_PROGRESS', { content: `로그인 화면입니다\n\n${OLD_SYNTAX}` });
    state.comments = [comment({ id: 4, author: SUPPORT_TEAM, content: `이렇게 해 보세요\n\n${imageSyntax('답변 그림', REPLY_IMAGE)}` })];
    state.attachments = [inlineAttachment(), inlineAttachment({ id: REPLY_IMAGE, fileName: 'reply.png', commentId: 4, uploadedBy: SUPPORT_TEAM })];
    renderAt(URL_7);
    await heading1();

    expect(srcPath(await imageIn(issueBody, '옛 화면')), '문의 본문의 이미지').toBe(attachmentPath(OLD_IMAGE));
    expect(srcPath(await imageIn(() => commentById(4), '답변 그림')), '댓글의 이미지').toBe(attachmentPath(REPLY_IMAGE));
    expect(text(issueBody()), '본문에 남은 문법 글자').not.toContain('attachment:');
    expect(text(issueBody())).toContain('로그인 화면입니다');
    expect(contentImagePaths().filter((path) => path.includes('/api/console')), '포털 화면의 콘솔 주소').toEqual([]);
  });

  test('S5-AC-52 P-5 읽지 못한 이미지: 깨진 그림 대신 설명과 "이미지를 볼 수 없습니다"를 보인다', async () => {
    withIssueImage();
    renderAt(URL_7);
    const img = await imageIn(issueBody, '옛 화면');

    fireEvent.error(img);

    await eventually(() => {
      expect(text(issueBody())).toContain(UNREADABLE);
    });
    expect(text(issueBody())).toContain('옛 화면');
    expect(issueBody()?.querySelectorAll('img')).toHaveLength(0);
  });

  test('S5-AC-50 P-5 axe: 이미지가 그려진 상세 화면에 접근성 위반이 없다', async () => {
    withIssueImage();
    renderAt(URL_7);
    await imageIn(issueBody, '옛 화면');

    await expectNoAxeViolations();
  });

  test.each(EDITORS)(
    'S5-AC-54 S5-AC-57 P-5 $editor: 편집기의 "이미지" 단추로 올리면 POST /api/portal/images가 나가고 글에 문법이 들어간다. 올리는 동안 "$save"가 눌리지 않는다. 저장하면 imageIds는 방금 올린 것뿐이다(글에 있던 이미지의 ID는 보내지 않는다)',
    async ({ prepare, open, scope, box, save, method, path }) => {
      prepare();
      const release = gate();
      on('post', IMAGES, async () => {
        await release.opened;
        return acceptImage();
      });
      const { user } = renderAt(URL_7);
      // A text that differs from the saved one: "저장" of an unchanged issue is blocked whether or not an upload runs.
      await user.type(await open(user), '더 적습니다');

      await pickImages(user, scope, imageFile('shot.png'));
      expect(text(await statusWith('이미지 올리는 중'))).toContain('shot.png');
      expect(isBlocked(await buttonIn(scope, save)), `올리는 동안 "${save}"`).toBe(true);
      await user.click(await buttonIn(scope, save));
      await settle();
      expect(callsTo(method, path), `올리는 동안 누른 "${save}"`).toHaveLength(0);

      release.open();
      await inserted(box, 'shot.png', NEW_IMAGE);
      expect(await uploadedImages()).toEqual(['shot.png']);
      await eventually(() => {
        expect(isBlocked(within(scope() as HTMLElement).getByRole('button', { name: save })), `올라간 뒤 "${save}"`).toBe(false);
      });
      await user.click(await buttonIn(scope, save));

      const [request] = await sent(method, path);
      const body = (await request?.body) as { content?: string };
      expect(body.content).toContain(OLD_SYNTAX);
      expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
      expect(await imageIdsOf(request)).toEqual([NEW_IMAGE]);
    },
  );

  test.each(EDITORS.filter(({ editor }) => editor !== '문의 고치기'))(
    'S5-AC-54 P-5 $editor, 올리는 중의 Ctrl+Enter: 편집칸에서 눌러도 저장 요청이 나가지 않고 쓰던 글이 남는다. 올라간 뒤에 누르면 나간다',
    async ({ prepare, open, scope, box, save, method, path }) => {
      prepare();
      const release = gate();
      on('post', IMAGES, async () => {
        await release.opened;
        return acceptImage();
      });
      const { user } = renderAt(URL_7);
      const el = await open(user);
      // A text that differs from the saved one, so that a save has something to send.
      await user.type(el, '더 적습니다');
      const draft = el.value;
      await pickImages(user, scope, imageFile('shot.png'));
      await statusWith('이미지 올리는 중');

      await user.click(el);
      await user.keyboard('{Control>}{Enter}{/Control}');
      await settle();
      expect(callsTo(method, path), '올리는 동안의 Ctrl+Enter').toHaveLength(0);
      expect((await box()).value, '쓰던 글').toBe(draft);

      release.open();
      await inserted(box, 'shot.png', NEW_IMAGE);
      await eventually(() => {
        expect(isBlocked(within(scope() as HTMLElement).getByRole('button', { name: save })), `올라간 뒤 "${save}"`).toBe(false);
      });
      await user.click(await box());
      await user.keyboard('{Control>}{Enter}{/Control}');

      const [request] = await sent(method, path);
      expect(await imageIdsOf(request)).toEqual([NEW_IMAGE]);
    },
  );

  test('S5-AC-57 P-5 댓글 쓰기: 올린 이미지를 글에서 뺀 뒤 보내면 그 ID를 보내지 않는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const box = await writeBox();
    await user.type(box, '화면은 빼고 보냅니다');
    await pickImages(user, writeFormNow, imageFile('shot.png'));
    await inserted(writeBox, 'shot.png', NEW_IMAGE);

    fireEvent.change(box, { target: { value: box.value.replace(`${imageSyntax('shot.png', NEW_IMAGE)}\n`, '') } });
    await user.click(await buttonIn(writeFormNow, SEND));

    const [post] = await sent('POST', COMMENTS_7);
    const body = (await post?.body) as { content: string };
    expect(body.content).toContain('화면은 빼고 보냅니다');
    expect(body.content).not.toContain(NEW_IMAGE);
    expect((await imageIdsOf(post)) ?? [], '글에서 뺀 이미지의 ID').toEqual([]);
  });

  test.each(EDITORS)(
    'S5-AC-56 S5-AC-63 P-5 미리 보기($editor): 방금 올린 이미지는 임시 주소(/api/portal/images/ID)로, 있던 이미지는 문의의 첨부 주소로 그린다',
    async ({ prepare, open, scope, box }) => {
      prepare();
      const { user } = renderAt(URL_7);
      await open(user);
      await pickImages(user, scope, imageFile('shot.png'));
      await inserted(box, 'shot.png', NEW_IMAGE);
      const preview = await showPreview(user, scope);

      expect(srcPath(await imageIn(preview, 'shot.png')), '방금 올린 이미지').toBe(temporaryPath(NEW_IMAGE));
      expect(srcPath(await imageIn(preview, '옛 화면')), '있던 이미지').toBe(attachmentPath(OLD_IMAGE));
      expect(contentImagePaths().filter((p) => p.includes('/api/console')), '포털 화면의 콘솔 주소').toEqual([]);
    },
  );

  test.each(EDITORS)(
    'S5-AC-58 P-5 $editor, 저장이 400 TEMPORARY_IMAGE_INVALID: 쓴 글이 남고 "이미지를 다시 올린 뒤 저장하세요"를 알린다',
    async ({ prepare, open, scope, box, save, method, route: route5 }) => {
      prepare();
      route(method === 'POST' ? 'post' : 'patch', route5, () => rejected('TEMPORARY_IMAGE_INVALID'));
      const { user } = renderAt(URL_7);
      await open(user);
      await pickImages(user, scope, imageFile('shot.png'));
      await inserted(box, 'shot.png', NEW_IMAGE);
      await user.click(await buttonIn(scope, save));

      await alertWith(STALE_IMAGE);
      const kept = (await box()).value;
      expect(kept).toContain(OLD_SYNTAX);
      expect(kept).toContain(imageSyntax('shot.png', NEW_IMAGE));
    },
  );

  test('S5-AC-57 P-5 댓글 고치기, 저장한 뒤: 올린 이미지는 묶인 것이 된다. 댓글에서는 첨부 주소로 그리고, 다시 고쳐 저장할 때 그 ID를 또 보내지 않는다', async () => {
    withMyComment();
    const { user } = renderAt(URL_7);
    await openCommentEdit(user);
    await pickImages(user, editForm, imageFile('shot.png'));
    await inserted(editBox, 'shot.png', NEW_IMAGE);
    await user.click(await buttonIn(editForm, SAVE_COMMENT));
    await said('댓글을 고쳤습니다');
    expect(await imageIdsOf(commentPatches()[0])).toEqual([NEW_IMAGE]);
    await eventually(() => {
      expect(editForm(), '보기로 돌아감').toBeNull();
    });

    expect(srcPath(await imageIn(() => commentById(5), 'shot.png')), '저장한 댓글의 이미지').toBe(attachmentPath(NEW_IMAGE));

    const again = await openCommentEdit(user);
    expect(again.value).toContain(imageSyntax('shot.png', NEW_IMAGE));
    await user.type(again, '다시 봤습니다');
    const preview = await showPreview(user, editForm);
    expect(srcPath(await imageIn(preview, 'shot.png')), '묶인 뒤의 미리 보기').toBe(attachmentPath(NEW_IMAGE));
    await user.click(await buttonIn(editForm, SAVE_COMMENT));

    await eventually(() => {
      expect(commentPatches()).toHaveLength(2);
    });
    const second = commentPatches()[1];
    expect(((await second?.body) as UpdateCommentRequest).content).toContain('다시 봤습니다');
    expect(await imageIdsOf(second), '이미 묶인 이미지뿐인 저장의 imageIds 칸').toBeUndefined();
  });

  test('S5-AC-57 P-5 문의 고치기: version과 바뀐 내용, imageIds만 보낸다. 이미지를 올리지 않고 글만 고치면 있던 이미지의 ID를 보내지 않는다', async () => {
    withIssueImage();
    const { user } = renderAt(URL_7);
    await user.type(await openIssueEdit(user), '\n다시 해 봤습니다');
    await user.click(await buttonIn(issueForm, '저장'));

    const [first] = await sent('PATCH', ISSUE_7);
    expect(((await first?.body) as UpdateIssueRequest).version).toBe(4);
    expect(await imageIdsOf(first), '글만 고친 저장의 imageIds 칸').toBeUndefined();
    await said('문의를 고쳤습니다');
    await savedBodyWith('다시 해 봤습니다');

    await openIssueEdit(user);
    await pickImages(user, issueForm, imageFile('shot.png'));
    await inserted(issueContentBox, 'shot.png', NEW_IMAGE);
    await user.click(await buttonIn(issueForm, '저장'));

    const patches = await sent('PATCH', ISSUE_7, 2);
    const body = (await patches[1]?.body) as UpdateIssueRequest;
    expect(Object.keys(body).sort()).toEqual(['content', 'imageIds', 'version']);
    expect(body.version).toBe(5);
    expect(body.imageIds).toEqual([NEW_IMAGE]);
  });

  test('S5-AC-57 P-5 문의 고치기, 저장한 뒤: 올린 이미지는 묶인 것이 된다. 다시 열어 글만 고쳐 저장하면 둘째 요청에 imageIds 칸이 없다', async () => {
    withIssueImage();
    const { user } = renderAt(URL_7);
    await openIssueEdit(user);
    await pickImages(user, issueForm, imageFile('shot.png'));
    await inserted(issueContentBox, 'shot.png', NEW_IMAGE);
    await user.click(await buttonIn(issueForm, '저장'));

    const [first] = await sent('PATCH', ISSUE_7);
    expect(await imageIdsOf(first)).toEqual([NEW_IMAGE]);
    await said('문의를 고쳤습니다');
    expect(srcPath(await imageIn(issueBody, 'shot.png')), '저장한 본문의 이미지').toBe(attachmentPath(NEW_IMAGE));

    const again = await openIssueEdit(user);
    expect(again.value).toContain(imageSyntax('shot.png', NEW_IMAGE));
    await user.type(again, '다시 봤습니다');
    await user.click(await buttonIn(issueForm, '저장'));

    const patches = await sent('PATCH', ISSUE_7, 2);
    const body = (await patches[1]?.body) as UpdateIssueRequest;
    expect(body.content).toContain('다시 봤습니다');
    expect(body.content).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(body.version).toBe(5);
    expect(Object.keys(body).sort(), '이미 묶인 이미지뿐인 저장의 칸').toEqual(['content', 'version']);
  });

  test('S5-AC-54 P-5 댓글 고치기를 그만둔 뒤 온 올리기 답: 아무것도 바꾸지 않는다. 다시 고치기를 열면 원래 글이다', async () => {
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

  test('S5-AC-59 P-5 첨부 목록: inline인 줄에만 "본문 이미지" 표시가 있다. 내려받기 링크는 다른 첨부와 같고 "지우기"로 지우면 흔적 줄이 된다', async () => {
    state.issue = issueIn('IN_PROGRESS', { content: `로그인 화면입니다\n\n${OLD_SYNTAX}` });
    state.attachments = [inlineAttachment(), attachment({ fileName: 'log.txt' })];
    const { user } = renderAt(URL_7);

    const image = await found(() => attachmentRow('old.png'), '첨부 줄 old.png');
    const plain = await found(() => attachmentRow('log.txt'), '첨부 줄 log.txt');
    expect(text(image), '본문 이미지의 줄').toContain('본문 이미지');
    expect(text(plain), '보통 첨부의 줄').not.toContain('본문 이미지');
    expect(hrefPath(within(image).getByRole('link', { name: 'old.png' }))).toBe(attachmentPath(OLD_IMAGE));
    expect(hrefPath(within(plain).getByRole('link', { name: 'log.txt' }))).toBe(attachmentPath(attachment().id));
    expect(within(plain).queryByRole('button', { name: /지우기/ }), '보통 첨부의 "지우기"').not.toBeNull();

    await user.click(within(image).getByRole('button', { name: /지우기/ }));
    await user.click(within(await dialog()).getByRole('button', { name: '지우기' }));

    await sent('DELETE', `${ATTACHMENTS_7}/${OLD_IMAGE}`);
    await eventually(() => {
      expect(attachmentRow('old.png'), '지운 본문 이미지의 줄').toBeNull();
      expect(attachmentRow('지워진 첨부'), '흔적 줄').not.toBeNull();
    });
    expect(attachmentRow('log.txt')).not.toBeNull();
  });
});
