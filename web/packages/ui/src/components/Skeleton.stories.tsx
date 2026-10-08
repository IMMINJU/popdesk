import type { Meta, StoryObj } from '@storybook/react-vite';
import { Skeleton } from './Skeleton';

const meta = {
  component: Skeleton,
  args: { shape: 'line', width: '20rem' },
} satisfies Meta<typeof Skeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Line: Story = {};
export const Lines: Story = { args: { count: 5 } };
export const Block: Story = { args: { shape: 'block', width: '20rem', height: '6rem' } };
export const Circle: Story = { args: { shape: 'circle', width: '32px', height: '32px' } };
/** Under prefers-reduced-motion the pulse stops (CSS only; switch it in the OS or devtools). */
export const ReducedMotion: Story = { args: { count: 3 } };
