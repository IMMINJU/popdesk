// 5 묶음 screens.md 1·2절(S5-AC-54~58): 편집 하나의 본문 이미지 훅 useEditorImages. 이번 편집에서 올린 이미지는 임시 주소로,
// 그 밖은 문의에 붙은 주소로 그리고, 저장하면 새 편집으로 돌아간다. 순수 함수 imageIdsToSend와 imageFailureText는
// bodyImages.send·text 테스트가 본다. 여기서는 훅이 그 둘을 어떻게 잇는지를 본다.
import type { ImageUploadResult } from '@popdesk/ui';
import { act, renderHook } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { IMAGE_TEXT, useEditorImages, type ImagePaths } from './bodyImages';
import type { Failure, Result } from './result';

const A = '0b0e7a52-3c1d-4f6a-9a51-6d2f0c1e8a01';
const B = '5f3c9d10-77aa-4e0b-8c2e-1a9b4d7e6f02';
const png = () => new File(['x'], 'shot.png', { type: 'image/png' });

/** Paths whose upload gives `answers` in turn. */
function paths(answers: Result<{ id: string }>[], attached = true): ImagePaths & { uploads: File[] } {
  const uploads: File[] = [];
  return {
    uploads,
    upload: (file) => {
      uploads.push(file);
      const next = answers.shift();
      if (!next) throw new Error('no answer left');
      return Promise.resolve(next);
    },
    temporaryHref: (id) => `/tmp/${id}`,
    ...(attached ? { attachedHref: (id: string) => `/attached/${id}` } : {}),
  };
}

const went = (id: string): Result<{ id: string }> => ({ ok: true, data: { id }, response: new Response() });
const failed = (failure: Failure): Result<{ id: string }> => ({ ok: false, failure });

type Editor = { onImageUpload: (f: File) => Promise<ImageUploadResult>; imageSrc: (id: string) => string | null; imageNoticesKey: number };

describe('useEditorImages', () => {
  test('S5-AC-54 paths가 없으면 편집기에 아무것도 주지 않고, 저장에 imageIds를 넣지 않는다', () => {
    const { result } = renderHook(() => useEditorImages(undefined));

    expect(result.current.editor).toEqual({});
    expect(result.current.idsFor(`![a](attachment:${A})`)).toEqual({});
    expect(result.current.busy).toBe(false);
  });

  test('S5-AC-55 올린 이미지는 임시 주소로, 올리지 않은 ID는 문의에 붙은 주소로 그린다', async () => {
    const p = paths([went(A)]);
    const { result } = renderHook(() => useEditorImages(p));
    const editor = () => result.current.editor as Editor;

    let answer: ImageUploadResult | undefined;
    await act(async () => {
      answer = await editor().onImageUpload(png());
    });

    expect(answer).toEqual({ ok: true, id: A });
    expect(p.uploads.map((f) => f.name)).toEqual(['shot.png']);
    expect(editor().imageSrc(A)).toBe(`/tmp/${A}`);
    expect(editor().imageSrc(B)).toBe(`/attached/${B}`);
  });

  test('S5-AC-55 문의가 아직 없으면(attachedHref 없음) 올리지 않은 ID는 그리지 않는다', async () => {
    const p = paths([went(A)], false);
    const { result } = renderHook(() => useEditorImages(p));
    const editor = () => result.current.editor as Editor;

    await act(async () => {
      await editor().onImageUpload(png());
    });

    expect(editor().imageSrc(A)).toBe(`/tmp/${A}`);
    expect(editor().imageSrc(B)).toBeNull();
  });

  test('S5-AC-56 올리지 못하면 그 까닭의 글을 주고, 그 이미지는 이번 편집의 것이 되지 않는다', async () => {
    const p = paths([failed({ kind: 'rejected', status: 400, code: 'FILE_NOT_IMAGE', message: 'm' })]);
    const { result } = renderHook(() => useEditorImages(p));
    const editor = () => result.current.editor as Editor;

    let answer: ImageUploadResult | undefined;
    await act(async () => {
      answer = await editor().onImageUpload(png());
    });

    expect(answer).toEqual({ ok: false, message: IMAGE_TEXT.notImage });
    expect(result.current.idsFor(`![a](attachment:${A})`)).toEqual({});
  });

  test('S5-AC-57 저장은 올린 것 가운데 글에 남은 것만 imageIds로 보내고, 없으면 칸을 뺀다', async () => {
    const p = paths([went(A), went(B)]);
    const { result } = renderHook(() => useEditorImages(p));
    const editor = () => result.current.editor as Editor;

    await act(async () => {
      await editor().onImageUpload(png());
      await editor().onImageUpload(png());
    });

    expect(result.current.idsFor(`앞 ![b](attachment:${B}) ![a](attachment:${A})`)).toEqual({ imageIds: [A, B] });
    expect(result.current.idsFor(`![b](attachment:${B})`)).toEqual({ imageIds: [B] });
    expect(result.current.idsFor('글만')).toEqual({});
  });

  test('S5-AC-57 saved 뒤는 새 편집이다: 앞 이미지는 붙은 주소로 그리고, 보낼 ID가 없고, 알림 열쇠가 바뀐다', async () => {
    const p = paths([went(A)]);
    const { result } = renderHook(() => useEditorImages(p));
    const editor = () => result.current.editor as Editor;
    await act(async () => {
      await editor().onImageUpload(png());
    });
    const keyBefore = editor().imageNoticesKey;

    act(() => {
      result.current.saved();
    });

    expect(editor().imageSrc(A)).toBe(`/attached/${A}`);
    expect(result.current.idsFor(`![a](attachment:${A})`)).toEqual({});
    expect(editor().imageNoticesKey).toBe(keyBefore + 1);
  });

  test('S5-AC-54 편집기가 올리는 중이라고 알리면 busy가 되고, 끝났다고 알리면 풀린다', () => {
    const { result } = renderHook(() => useEditorImages(paths([])));
    const busyChange = () => (result.current.editor as { onImageBusyChange: (b: boolean) => void }).onImageBusyChange;

    act(() => {
      busyChange()(true);
    });
    expect(result.current.busy).toBe(true);
    act(() => {
      busyChange()(false);
    });
    expect(result.current.busy).toBe(false);
  });
});
