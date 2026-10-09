// screens.md 5절 21 FileUpload. 비어 있음, 끌어 놓는 중, 고름(파일 줄), 올리는 중(진행 막대), 실패(이유, 다시 올리기), 끝남.
// 화면 쪽 크기 검사: 10MB 넘는 파일, 합 50MB 넘음, 빈 파일, 이름 255자 넘음(ATT-2).
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { MB, expectNoAxeViolations, file, found, text } from '../__tests__/support';
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

type Rejected = { file: File; reason: FileRejectReason }[];

function lastSelect(onSelect: ReturnType<typeof vi.fn>): { accepted: string[]; rejected: [string, FileRejectReason][] } {
  expect(onSelect).toHaveBeenCalled();
  const [accepted, rejected] = onSelect.mock.lastCall as [File[], Rejected];
  return { accepted: accepted.map((f) => f.name), rejected: rejected.map((r) => [r.file.name, r.reason]) };
}

const row = (name: string) =>
  found(() => screen.queryAllByRole('listitem').find((li) => text(li).includes(name)), `파일 줄 ${name}`);

describe('FileUpload', () => {
  test('FileUpload 비어 있음: 이름표로 찾는 파일 칸, 여러 개를 고를 수 있다', async () => {
    render(upload({ helpText: '파일 하나 10MB까지' }));

    const i = await input();
    expect(i).toHaveAttribute('multiple');
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  test('FileUpload 크기 검사: 10MB 넘는 파일, 빈 파일, 이름 255자 넘는 파일을 이유와 함께 막고 나머지만 받는다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect }));
    const user = userEvent.setup();

    const longName = `${'가'.repeat(252)}.txt`;
    await user.upload(await input(), [
      file('ok.txt', 1024),
      file('big.bin', 10 * MB + 1),
      file('empty.txt', 0),
      file(longName, 10),
      file('edge.bin', 10 * MB),
    ]);

    const { accepted, rejected } = lastSelect(onSelect);
    expect(accepted).toEqual(['ok.txt', 'edge.bin']);
    expect(rejected).toEqual([
      ['big.bin', 'too-large'],
      ['empty.txt', 'empty'],
      [longName, 'name-too-long'],
    ]);
  });

  test('FileUpload 합 검사: 이미 고른 파일과 합해 50MB를 넘게 하는 파일은 total-too-large', async () => {
    const onSelect = vi.fn();
    const items: UploadItem[] = [
      { id: 'u1', name: 'a.bin', size: 9 * MB, state: 'done' },
      { id: 'u2', name: 'b.bin', size: 9 * MB, state: 'done' },
      { id: 'u3', name: 'c.bin', size: 9 * MB, state: 'done' },
      { id: 'u4', name: 'd.bin', size: 9 * MB, state: 'done' },
      { id: 'u5', name: 'e.bin', size: 9 * MB, state: 'done' },
    ];
    render(upload({ onSelect, items }));
    const user = userEvent.setup();

    await user.upload(await input(), [file('small.bin', 4 * MB), file('over.bin', 2 * MB)]);

    const { accepted, rejected } = lastSelect(onSelect);
    expect(accepted).toEqual(['small.bin']);
    expect(rejected).toEqual([['over.bin', 'total-too-large']]);
  });

  test('FileUpload 끌어 놓기: 떨어뜨린 파일도 같은 검사를 거쳐 onSelect로 나간다', async () => {
    const onSelect = vi.fn();
    render(upload({ onSelect }));
    await input();

    const zone = screen.getByText('파일 첨부');
    fireEvent.dragEnter(zone, { dataTransfer: { files: [file('drop.txt', 10)], types: ['Files'] } });
    fireEvent.dragOver(zone, { dataTransfer: { files: [file('drop.txt', 10)], types: ['Files'] } });
    fireEvent.drop(zone, { dataTransfer: { files: [file('drop.txt', 10), file('void.txt', 0)], types: ['Files'] } });

    const { accepted, rejected } = lastSelect(onSelect);
    expect(accepted).toEqual(['drop.txt']);
    expect(rejected).toEqual([['void.txt', 'empty']]);
  });

  test('FileUpload 고름: 파일 줄마다 이름, 크기, "빼기" 버튼은 onRemove(id)', async () => {
    const onRemove = vi.fn();
    render(upload({ onRemove, items: [{ id: 'u1', name: 'report.pdf', size: 2 * MB, state: 'selected' }] }));
    const user = userEvent.setup();

    const r = await row('report.pdf');
    expect(text(r)).toMatch(/2(\.0)?\s?MB/);
    await user.click(await found(() => within(r).queryByRole('button', { name: /빼기/ }), '빼기 버튼'));
    expect(onRemove).toHaveBeenCalledWith('u1');
  });

  test('FileUpload 올리는 중: 그 파일 줄에 파일 이름으로 불리는 progressbar와 지금 비율', async () => {
    render(upload({ items: [{ id: 'u1', name: 'trace.log', size: MB, state: 'uploading', progress: 0.5 }] }));

    const bar = await found(() => screen.queryByRole('progressbar', { name: /trace\.log/ }), 'trace.log 진행 막대');
    expect(bar).toHaveAttribute('aria-valuenow', '50');
  });

  test('FileUpload 실패: 그 파일 줄에 이유, "다시 올리기"는 onRetry(id), "빼기"는 onRemove(id)', async () => {
    const onRetry = vi.fn();
    const onRemove = vi.fn();
    render(
      upload({
        onRetry,
        onRemove,
        items: [{ id: 'u9', name: 'dump.bin', size: 9 * MB, state: 'failed', error: '파일이 서버 앞단의 크기 상한에 걸렸습니다' }],
      }),
    );
    const user = userEvent.setup();

    const r = await row('dump.bin');
    expect(text(r)).toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    await user.click(await found(() => within(r).queryByRole('button', { name: /다시 올리기/ }), '다시 올리기 버튼'));
    expect(onRetry).toHaveBeenCalledWith('u9');
    await user.click(await found(() => within(r).queryByRole('button', { name: /빼기/ }), '빼기 버튼'));
    expect(onRemove).toHaveBeenCalledWith('u9');
  });

  test('FileUpload 화면에서 막음: 그 파일 줄에 이유를 보이고 다시 올리기는 없다', async () => {
    render(upload({ items: [{ id: 'r1', name: 'big.bin', size: 11 * MB, state: 'rejected', error: '파일 하나는 10MB까지입니다.' }] }));

    const r = await row('big.bin');
    expect(text(r)).toContain('파일 하나는 10MB까지입니다.');
    expect(within(r).queryByRole('button', { name: /다시 올리기/ })).toBeNull();
  });

  test('FileUpload 막힘: 파일 칸을 막는다', async () => {
    render(upload({ disabled: true }));

    expect(await input()).toBeDisabled();
  });

  test('FileUpload axe: 고름·올리는 중·실패 줄에 접근성 위반이 없다', async () => {
    render(
      upload({
        items: [
          { id: 'u1', name: 'report.pdf', size: 2 * MB, state: 'selected' },
          { id: 'u2', name: 'trace.log', size: MB, state: 'uploading', progress: 0.3 },
          { id: 'u3', name: 'dump.bin', size: 9 * MB, state: 'failed', error: '파일이 서버 앞단의 크기 상한에 걸렸습니다' },
        ],
      }),
    );
    await input();

    await expectNoAxeViolations();
  });
});
