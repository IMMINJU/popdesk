import type { Meta, StoryObj } from '@storybook/react-vite';
import { Avatar } from './Avatar';

const meta = {
  component: Avatar,
  args: { name: '김하나' },
} satisfies Meta<typeof Avatar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Initials: Story = {};
/** Portal: 지원팀 mark instead of an agent's photo and name. */
export const Support: Story = { args: { kind: 'support', name: '지원팀' } };
/** Photos come in slice 1 (D-52). */
export const Photo: Story = { args: { photoUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=' } };
export const Small: Story = { args: { size: 'sm' } };
export const Medium: Story = { args: { size: 'md' } };
export const Decorative: Story = { args: { decorative: true } };
