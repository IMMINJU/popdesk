import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { PriorityPicker } from './PriorityPicker';

const meta = {
  component: PriorityPicker,
  args: { legend: '우선순위', value: 'NORMAL', onChange: fn() },
} satisfies Meta<typeof PriorityPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Selected: Story = {};
export const NoneSelected: Story = { args: { value: null } };
export const Focus: Story = { play: focusFirst('input:checked') };
export const Disabled: Story = { args: { disabled: true } };
export const Error: Story = { args: { value: null, error: '우선순위를 고르세요.' } };
