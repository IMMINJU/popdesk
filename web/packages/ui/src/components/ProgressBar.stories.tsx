import type { Meta, StoryObj } from '@storybook/react-vite';
import { ProgressBar } from './ProgressBar';

const meta = {
  component: ProgressBar,
  args: { label: 'screenshot.png' },
} satisfies Meta<typeof ProgressBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Determinate: Story = { args: { value: 0.4 } };
export const Indeterminate: Story = {};
