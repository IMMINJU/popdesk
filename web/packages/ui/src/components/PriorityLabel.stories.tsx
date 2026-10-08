import type { Meta, StoryObj } from '@storybook/react-vite';
import { PriorityLabel } from './PriorityLabel';

const meta = {
  component: PriorityLabel,
  args: { priority: 'NORMAL' },
} satisfies Meta<typeof PriorityLabel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Low: Story = { args: { priority: 'LOW' } };
export const Normal: Story = {};
export const High: Story = { args: { priority: 'HIGH' } };
export const Urgent: Story = { args: { priority: 'URGENT' } };
