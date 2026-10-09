// screens.md 5절 21 FileUpload (ATT-2). C-10 변이 테스트가 찾은 빈칸: 상한과 같은 값, 합에 넣는 것과 빼는 것,
// 막힘·빈 고름·한 개만, 끌어 놓기 허용 조건, 도움말 연결, 줄마다 버튼과 진행 막대가 나오는 상태.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { MB, describedText, file, found, text } from '../__tests__/support';
import { FileUpload, type FileRejectReason, type FileUploadProps, type UploadItem } from './FileUpload';

const limits = { maxFileBytes: 10 * MB, maxTotalBytes: 50 * MB, maxNameLength: 255 };

function upload(over: Partial<FileUploadProps> = {}) {
  return (
    <FileUpload
      label="파일 첨부"
      items={[]}
      onSelect={() => undefined}
      onRemove={() => undefined}
      onRetry={() => undefined}
      limits={limits}
      removeLabel="빼기"
      retryLabel="다시 올리기"
      {...over}
    />
  );
}

const input = () =>
  found(() => screen.queryAllByLabelText<HTMLInputElement>('파일 첨부', { selector: 'input[type="file"]' })[0], '파일 칸 "파일 첨부"');

function pick(el: HTMLInputElement, files: File[]) {
  fireEvent.change(el, { target: { files } });
}

function lastSelect(onSelect: ReturnType<typeof vi.fn>): { accepted: string[]; rejected: [string, FileRejectReason][] } {
  expect(onSelect).toHaveBeenCalled();
  const [accepted, rejected] = onSelect.mock.lastCall as [File[], { file: File; reason: FileRejectReason }[]];
  return { accepted: accepted.map((f) => f.name), rejected: rejected.map((r) => [r.file.name, r.reason]) };
}

const item = (over: Partial<UploadItem>): UploadItem => ({ id: 'f1', name: 'log.txt', size: 1024, state: 'selected', ...over });

const zone = () => screen.getByText('파일 첨부');
const drag = (types: string[], files: File[] = []) => ({ dataTransfer: { files, types } });

describe('FileUpload 동작', () => {
  test('FileUpload 상한과 같은 값: 이름 255자, 합이 꼭 50MB인 파일은 받는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect, items: [item({ id: 'big', size: 40 * MB, state: 'done' })] }));

    pick(await input(), [file(`${'가'.repeat(251)}.txt`, 10), file('fill.bin', 10 * MB - 10)]);
    expect(lastSelect(onSelect)).toEqual({ accepted: [`${'가'.repeat(251)}.txt`, 'fill.bin'], rejected: [] });
  });

  test('FileUpload 합: 화면에서 막은 줄은 합에 넣지 않는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect, usedBytes: 35 * MB, items: [item({ id: 'no', size: 10 * MB, state: 'rejected', error: '너무 큽니다' })] }));

    pick(await input(), [file('ten.bin', 10 * MB)]);
    expect(lastSelect(onSelect)).toEqual({ accepted: ['ten.bin'], rejected: [] });
  });

  test('FileUpload 합: 이미 있는 첨부(usedBytes)는 합에 넣는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect, usedBytes: 45 * MB }));

    pick(await input(), [file('ten.bin', 10 * MB)]);
    expect(lastSelect(onSelect)).toEqual({ accepted: [], rejected: [['ten.bin', 'total-too-large']] });
  });

  test('FileUpload 한 개만: multiple={false}면 여러 개를 놓아도 첫 파일만 검사해 넘긴다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect, multiple: false }));
    await input();

    fireEvent.drop(zone(), drag(['Files'], [file('one.txt', 10), file('two.txt', 10)]));
    expect(lastSelect(onSelect)).toEqual({ accepted: ['one.txt'], rejected: [] });
    expect(await input()).not.toHaveAttribute('multiple');
  });

  test('FileUpload 막힘: 끌어 놓아도 onSelect를 부르지 않고 끌기를 받지 않는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect, disabled: true }));
    await input();

    expect(fireEvent.dragEnter(zone(), drag(['Files']))).toBe(true);
    expect(fireEvent.dragOver(zone(), drag(['Files']))).toBe(true);
    fireEvent.drop(zone(), drag(['Files'], [file('drop.txt', 10)]));
    expect(onSelect).not.toHaveBeenCalled();
  });

  test('FileUpload 빈 고름: 고른 파일이 없으면 onSelect를 부르지 않는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect }));

    pick(await input(), []);
    expect(onSelect).not.toHaveBeenCalled();
  });

  test('FileUpload 끌기: 파일을 끌 때만 놓기를 받는다(기본 동작을 막는다), 글자를 끌면 받지 않는다', async () => {
    render(upload());
    await input();

    expect(fireEvent.dragEnter(zone(), drag(['Files']))).toBe(false);
    expect(fireEvent.dragOver(zone(), drag(['Files']))).toBe(false);
    expect(fireEvent.dragEnter(zone(), drag(['text/plain']))).toBe(true);
    expect(fireEvent.dragOver(zone(), drag(['text/plain']))).toBe(true);
  });

  test('FileUpload 놓기: 놓은 파일을 브라우저가 열지 않게 기본 동작을 막는다', async () => {
    render(upload());
    await input();

    expect(fireEvent.drop(zone(), drag(['Files'], [file('drop.txt', 10)]))).toBe(false);
  });

  test('FileUpload 도움말: 파일 칸의 aria-describedby로 잇고, 없으면 잇지 않는다', async () => {
    const { rerender } = render(upload({ helpText: '파일 하나 10MB까지' }));
    expect(describedText(await input())).toBe('파일 하나 10MB까지');

    rerender(upload());
    expect(await input()).not.toHaveAttribute('aria-describedby');
  });

  test('FileUpload 줄 없음: 파일이 없으면 목록을 그리지 않는다', async () => {
    render(upload());
    await input();

    expect(screen.queryByRole('list')).toBeNull();
  });

  test('FileUpload 줄 버튼 설명: 빼기·다시 올리기 버튼이 그 줄의 파일 이름으로 설명된다', async () => {
    render(upload({ items: [item({ id: 'a', name: 'a.txt' }), item({ id: 'b', name: 'b.txt', state: 'failed', error: '실패' })] }));
    await input();

    const rows = screen.getAllByRole('listitem');
    expect(describedText(within(rows[0] as HTMLElement).getByRole('button', { name: '빼기' }))).toBe('a.txt');
    expect(describedText(within(rows[1] as HTMLElement).getByRole('button', { name: '다시 올리기' }))).toBe('b.txt');
    expect(describedText(within(rows[1] as HTMLElement).getByRole('button', { name: '빼기' }))).toBe('b.txt');
  });

  test('FileUpload 올리는 중: 빼기 버튼이 없고, 오류 문구가 있어도 보이지 않는다', async () => {
    render(upload({ items: [item({ state: 'uploading', progress: 0.5, error: '이전 실패' })] }));
    await input();

    const row = screen.getByRole('listitem');
    expect(within(row).queryByRole('button')).toBeNull();
    expect(text(row)).not.toContain('이전 실패');
  });

  test('FileUpload 끝남·고름: 진행 막대가 없다', async () => {
    render(upload({ items: [item({ id: 'a', state: 'done' }), item({ id: 'b', name: 'b.txt', state: 'selected' })] }));
    await input();

    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  test('FileUpload 크기: 줄마다 크기를 B·KB·MB로 보인다', async () => {
    render(upload({ items: [item({ id: 'a', size: 512 }), item({ id: 'b', name: 'b.txt', size: 2048 }), item({ id: 'c', name: 'c.bin', size: 9.5 * MB })] }));
    await input();

    expect(screen.getAllByRole('listitem').map((li) => text(li))).toEqual([
      expect.stringContaining('512 B'),
      expect.stringContaining('2.0 KB'),
      expect.stringContaining('9.5 MB'),
    ]);
  });
});
