import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn, userEvent } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { MarkdownEditor } from './MarkdownEditor';

const meta = {
  component: MarkdownEditor,
  args: {
    label: '댓글',
    value: '**확인했습니다.** 로그를 첨부해 주세요.',
    onChange: fn(),
    onModeChange: fn(),
    minHeight: 112,
  },
} satisfies Meta<typeof MarkdownEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Write: Story = { args: { mode: 'write' } };
export const Preview: Story = { args: { mode: 'preview' } };
export const Focus: Story = { play: focusFirst('textarea') };
export const Error: Story = { args: { value: '', error: '내용을 넣으세요.' } };
export const Disabled: Story = { args: { disabled: true } };
/** C-4 내부 메모 모드. */
export const Internal: Story = { args: { internal: true, label: '내부 메모', onSubmitShortcut: fn() } };
export const WithCount: Story = { args: { maxLength: 4000, showCount: true } };
/** P-4 문의 내용: 420px. */
export const IssueBody: Story = { args: { label: '내용', value: '', minHeight: 420, maxLength: 10000 } };

// 이미지 (5-attachments screens.md 1절·4절). The states come from what a writer does, so `play` does it.
const png = () => new File(['x'], 'shot.png', { type: 'image/png' });
const pick = async (canvasElement: HTMLElement) => {
  const input = canvasElement.querySelector<HTMLInputElement>('input[type="file"]');
  if (input) await userEvent.upload(input, png());
};

/** 올리는 중: the upload never ends here. The line under the text area is a `role="status"`. */
export const ImageUploading: Story = {
  args: { onImageUpload: () => new Promise(() => undefined), onImageBusyChange: fn() },
  play: ({ canvasElement }) => pick(canvasElement),
};
/** 실패: the text is untouched and the reason is a `role="alert"` under the text area. */
export const ImageFailed: Story = {
  args: { onImageUpload: () => Promise.resolve({ ok: false, message: 'PNG, JPEG, GIF, WebP 이미지만 본문에 넣을 수 있습니다' }) },
  play: ({ canvasElement }) => pick(canvasElement),
};
/** 끌어 오는 중: a dashed line around the text area and a line of text, not colour alone. */
export const ImageDragging: Story = {
  args: { onImageUpload: () => new Promise(() => undefined) },
  play: ({ canvasElement }) => {
    const area = canvasElement.querySelector('textarea');
    // A browser takes only its own DataTransfer in a drag event.
    const dataTransfer = new DataTransfer();
    dataTransfer.items.add(png());
    area?.dispatchEvent(new DragEvent('dragenter', { dataTransfer, bubbles: true, cancelable: true }));
  },
};
