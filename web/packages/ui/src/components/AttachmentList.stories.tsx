import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { AttachmentList } from './AttachmentList';

const meta = {
  component: AttachmentList,
  args: {
    title: '첨부',
    emptyText: '첨부 없음',
    deleteLabel: '지우기',
    deletedLabel: '지워진 첨부',
    internalLabel: '내부',
    onDelete: fn(),
    items: [
      {
        id: '1',
        name: 'screenshot.png',
        size: 240_000,
        href: '#',
        uploaderLabel: '나',
        createdAt: '2026-10-09T01:00:00Z',
        canDelete: true,
      },
      {
        id: '2',
        name: 'guide.pdf',
        size: 1_200_000,
        href: '#',
        uploaderLabel: '지원팀',
        createdAt: '2026-10-09T02:00:00Z',
      },
    ],
  },
} satisfies Meta<typeof AttachmentList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithData: Story = {};
export const Empty: Story = { args: { items: [] } };
export const Deleted: Story = {
  args: {
    items: [
      { id: '1', name: 'screenshot.png', size: 240_000, uploaderLabel: '나', createdAt: '2026-10-09T01:00:00Z', deleted: true },
    ],
  },
};
export const Internal: Story = {
  args: {
    items: [
      {
        id: '3',
        name: 'server.log',
        size: 40_000,
        href: '#',
        uploaderLabel: '박상담',
        createdAt: '2026-10-09T03:00:00Z',
        internal: true,
        canDelete: true,
      },
    ],
  },
};
export const Deleting: Story = {
  args: {
    items: [
      {
        id: '1',
        name: 'screenshot.png',
        size: 240_000,
        href: '#',
        uploaderLabel: '나',
        createdAt: '2026-10-09T01:00:00Z',
        canDelete: true,
        deleting: true,
      },
    ],
  },
};
/** 본문 이미지 표시 (5-attachments screens.md 3절): listed, downloaded and deleted like any other attachment. */
export const InlineImage: Story = {
  args: {
    items: [
      { id: '1', name: 'shot.png', size: 240_000, href: '#', uploaderLabel: '나', createdAt: '2026-10-09T01:00:00Z', canDelete: true, inline: true },
      { id: '2', name: 'guide.pdf', size: 1_200_000, href: '#', uploaderLabel: '지원팀', createdAt: '2026-10-09T02:00:00Z' },
    ],
  },
};
