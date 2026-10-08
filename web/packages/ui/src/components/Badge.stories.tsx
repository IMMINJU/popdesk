import type { Meta, StoryObj } from '@storybook/react-vite';
import { Badge } from './Badge';

const meta = {
  component: Badge,
  args: { tone: 'neutral', children: '비공개' },
} satisfies Meta<typeof Badge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Neutral: Story = {};
export const Accent: Story = { args: { tone: 'accent', children: '나' } };
export const Warning: Story = { args: { tone: 'warning', children: '수락 전' } };
export const Internal: Story = { args: { tone: 'internal', children: '내부' } };
export const NotConfigured: Story = { args: { tone: 'neutral', children: '설정 없음' } };
