import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { FileUpload } from './FileUpload';

const MB = 1024 * 1024;

const meta = {
  component: FileUpload,
  args: {
    label: '파일 첨부',
    helpText: '파일 하나 10MB, 합 50MB까지',
    items: [],
    limits: { maxFileBytes: 10 * MB, maxTotalBytes: 50 * MB, maxNameLength: 255 },
    onSelect: fn(),
    onRemove: fn(),
    onRetry: fn(),
    removeLabel: '빼기',
    retryLabel: '다시 올리기',
  },
} satisfies Meta<typeof FileUpload>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};
/** 끌어 놓는 중 is tracked inside the component while a file is dragged over it. */
export const Dragging: Story = {};
export const Selected: Story = {
  args: { items: [{ id: '1', name: 'screenshot.png', size: 240_000, state: 'selected' }] },
};
export const Uploading: Story = {
  args: {
    items: [
      { id: '1', name: 'screenshot.png', size: 240_000, state: 'uploading', progress: 0.4 },
      { id: '2', name: 'log.txt', size: 12_000, state: 'uploading' },
    ],
  },
};
export const Failed: Story = {
  args: {
    items: [
      {
        id: '1',
        name: 'dump.zip',
        size: 9 * MB,
        state: 'failed',
        error: '파일이 서버 앞단의 크기 상한에 걸렸습니다',
      },
    ],
  },
};
export const Rejected: Story = {
  args: {
    items: [{ id: '1', name: 'video.mp4', size: 80 * MB, state: 'rejected', error: '10MB를 넘습니다' }],
  },
};
export const Done: Story = {
  args: { items: [{ id: '1', name: 'screenshot.png', size: 240_000, state: 'done' }] },
};
