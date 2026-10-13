// 5번 묶음 screens.md 1절 편집기의 이미지, C-19 대리 등록의 "내용" 편집기(S5-AC-54~58).
// 단추, 붙여 넣기, 끌어다 놓기와 커서 자리는 @popdesk/ui의 MarkdownEditor 테스트가 본다. 여기는 화면이 올리기를
// 콘솔의 경로로 보내고, 실패의 글을 고르고, 저장할 때 본문에 남은 이미지의 ID만 보내는지를 본다.
import { fireEvent } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  field,
  found,
  gate,
  isBlocked,
  on,
  renderAt,
  screen,
  sent,
  statusWith,
  text,
} from '../__tests__/console';
import { rejected } from '../__tests__/console1a';
import { ISSUES, KIM, select, type CreateIssueRequest } from '../__tests__/console3';
import {
  IMAGES,
  NEW_IMAGE,
  NEW_IMAGE_2,
  acceptImage,
  imageButtonIn,
  imageFile,
  imageIdsOf,
  imageInputOf,
  imageSyntax,
  pickImages,
  proxyPayloadTooLarge,
  uploadedImages,
} from '../__tests__/console5';

const URL_NEW = '/issues/new';
const STALE_IMAGE = '본문의 이미지 가운데 올린 지 오래된 것이 있습니다. 이미지를 다시 올린 뒤 저장하세요';

type User = ReturnType<typeof renderAt>['user'];

const main = () => screen.queryByRole('main');
const titleBox = () => field('제목', 'input');
const contentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
const submit = () => button('등록');
const customer = (line: string) =>
  screen.queryAllByRole<HTMLInputElement>('radio').find((r) => r.labels?.[0] && text(r.labels[0]).includes(line)) ??
  screen.queryAllByRole('option').find((o) => text(o).includes(line)) ??
  null;

/** Picks ACME and 김고객 and writes a title: the least a form needs. */
async function fill(user: User, title = '전화로 받은 문의') {
  await user.selectOptions(await select(/^고객사/), ACME.name);
  await user.click(await found(() => customer(`${KIM.name} (${KIM.email})`), `고객 "${KIM.name}"`));
  await user.type(await titleBox(), title);
}

/** Waits until the editor holds the markdown of the uploaded image. */
async function inserted(name: string, id: string) {
  const box = await contentBox();
  await eventually(() => {
    expect(box.value, '편집칸의 글').toContain(`${imageSyntax(name, id)}\n`);
  });
}

describe('C-19 대리 등록: 본문 이미지', () => {
  test('S5-AC-54 C-19 "내용" 편집기에 "이미지" 단추가 있고 이미지 파일을 고르는 입력과 이어져 있다. 고르면 POST /api/console/images에 multipart의 file로 올리고 올라가면 본문에 ![파일 이름](attachment:ID)와 줄 바꿈을 넣는다', async () => {
    const { user } = renderAt(URL_NEW);
    await found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목');
    await contentBox();

    const image = await found(() => imageButtonIn(main), '"내용" 편집기의 "이미지" 단추');
    expect(imageInputOf(image), '"이미지" 단추와 이어진 이미지 파일 입력').not.toBeNull();
    await pickImages(user, main, imageFile('shot.png'));

    await sent('POST', IMAGES);
    expect(await uploadedImages()).toEqual(['shot.png']);
    expect(callsTo('POST', IMAGES)[0]?.headers.get('content-type') ?? '').toContain('multipart/form-data');
    await inserted('shot.png', NEW_IMAGE);
  });

  test('S5-AC-54 C-19 올리는 동안: role="status"로 "이미지 올리는 중"과 파일 이름을 알리고 "등록"이 눌리지 않는다. 편집칸은 계속 쓸 수 있고, 올라가면 "등록"이 풀린다', async () => {
    const release = gate();
    on('post', IMAGES, async () => {
      await release.opened;
      return acceptImage();
    });
    const { user } = renderAt(URL_NEW);
    await fill(user);
    await pickImages(user, main, imageFile('shot.png'));

    const busy = await statusWith('이미지 올리는 중');
    expect(text(busy)).toContain('shot.png');
    expect(isBlocked(await submit()), '올리는 동안 "등록"').toBe(true);
    await user.click(await submit());
    expect(callsTo('POST', ISSUES), '올리는 동안 누른 "등록"').toHaveLength(0);
    expect(await contentBox(), '올리는 동안 편집칸').not.toBeDisabled();

    release.open();
    await inserted('shot.png', NEW_IMAGE);
    await eventually(() => {
      expect(isBlocked(screen.getByRole('button', { name: '등록' })), '올라간 뒤 "등록"').toBe(false);
    });
  });

  test('S5-AC-57 C-19 저장: 둘을 올리고 하나를 본문에서 지운 뒤 등록하면 imageIds는 본문에 남은 것뿐이다', async () => {
    const { user } = renderAt(URL_NEW);
    await fill(user);
    await pickImages(user, main, imageFile('first.png'));
    await inserted('first.png', NEW_IMAGE);
    await pickImages(user, main, imageFile('second.png'));
    await inserted('second.png', NEW_IMAGE_2);

    const box = await contentBox();
    const kept = box.value.replace(imageSyntax('second.png', NEW_IMAGE_2), '');
    fireEvent.change(box, { target: { value: kept } });
    await user.click(await submit());

    const [create] = await sent('POST', ISSUES);
    const body = (await create?.body) as CreateIssueRequest;
    expect(body.content).toContain(imageSyntax('first.png', NEW_IMAGE));
    expect(body.content).not.toContain(NEW_IMAGE_2);
    expect(await imageIdsOf(create)).toEqual([NEW_IMAGE]);
    await atPath('/issues/ACME-8');
  });

  test.each([
    { why: 'FILE_NOT_IMAGE', answer: () => rejected('FILE_NOT_IMAGE'), says: 'PNG, JPEG, GIF, WebP 이미지만 본문에 넣을 수 있습니다' },
    { why: 'FILE_TOO_LARGE', answer: () => rejected('FILE_TOO_LARGE'), says: '파일 하나는 10MB까지입니다.' },
    { why: 'FILE_EMPTY', answer: () => rejected('FILE_EMPTY'), says: '빈 파일은 올릴 수 없습니다.' },
    {
      why: 'TEMPORARY_IMAGE_LIMIT_EXCEEDED',
      answer: () => rejected('TEMPORARY_IMAGE_LIMIT_EXCEEDED'),
      says: '저장하지 않은 이미지가 너무 많습니다. 글을 저장한 뒤 다시 올리세요',
    },
    { why: 'ATTACHMENT_QUOTA_EXCEEDED', answer: () => rejected('ATTACHMENT_QUOTA_EXCEEDED'), says: '팀의 첨부 용량이 모자랍니다. 관리자에게 알려 주세요.' },
    { why: '413(앞단의 답)', answer: () => proxyPayloadTooLarge(), says: '파일이 서버 앞단의 크기 상한에 걸렸습니다' },
    { why: '429', answer: () => errors.rateLimited(30), says: '요청이 많습니다. 30초 뒤 다시 하세요.' },
    { why: '연결 끊김', answer: () => HttpResponse.error(), says: '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.' },
  ])('S5-AC-55 C-19 올리기 실패($why): role="alert"로 "$says"를 보이고 본문은 그대로다', async ({ answer, says }) => {
    on('post', IMAGES, () => answer());
    const { user } = renderAt(URL_NEW);
    await user.type(await contentBox(), '전화로 들은 내용입니다.');
    await pickImages(user, main, imageFile('shot.png'));

    await alertWith(says);
    expect(await contentBox()).toHaveValue('전화로 들은 내용입니다.');
    expect(screen.queryAllByRole('status').some((el) => text(el).includes('이미지 올리는 중')), '실패한 뒤 "올리는 중"').toBe(false);
    expect(isBlocked(await submit()), '실패한 뒤 "등록"').toBe(false);
  });

  test('S5-AC-58 C-19 저장이 400 TEMPORARY_IMAGE_INVALID: 쓴 글을 지우지 않고 화면에 머물며 "이미지를 다시 올린 뒤 저장하세요"를 알린다', async () => {
    on('post', ISSUES, () => rejected('TEMPORARY_IMAGE_INVALID'));
    const { user } = renderAt(URL_NEW);
    await fill(user, '화면이 깨집니다');
    await pickImages(user, main, imageFile('shot.png'));
    await inserted('shot.png', NEW_IMAGE);
    await user.click(await submit());

    await alertWith(STALE_IMAGE);
    expect(await titleBox()).toHaveValue('화면이 깨집니다');
    expect((await contentBox()).value).toContain(imageSyntax('shot.png', NEW_IMAGE));
    expect(window.location.pathname).toBe(URL_NEW);
  });

  test('S5-AC-54 C-19 axe: "이미지" 단추가 있는 등록 화면에 접근성 위반이 없다', async () => {
    renderAt(URL_NEW);
    await contentBox();
    await found(() => imageButtonIn(main), '"내용" 편집기의 "이미지" 단추');

    await expectNoAxeViolations();
  });
});
