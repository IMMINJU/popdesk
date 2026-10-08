import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { Pagination } from './Pagination';

const meta = {
  component: Pagination,
  args: {
    label: '쪽 넘기기',
    page: 1,
    pageCount: 7,
    getHref: (p) => `?page=${String(p)}`,
    onPageChange: fn(),
  },
} satisfies Meta<typeof Pagination>;

export default meta;
type Story = StoryObj<typeof meta>;

export const First: Story = {};
export const Middle: Story = { args: { page: 4 } };
export const Last: Story = { args: { page: 7 } };
/** 한 쪽뿐: renders nothing. */
export const SinglePage: Story = { args: { pageCount: 1 } };
