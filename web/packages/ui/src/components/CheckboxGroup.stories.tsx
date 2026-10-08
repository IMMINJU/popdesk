import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { CheckboxGroup } from './CheckboxGroup';

const meta = {
  component: CheckboxGroup,
  args: {
    legend: '소속 고객사',
    options: [
      { value: '1', label: 'Acme' },
      { value: '2', label: 'Globex' },
      { value: '3', label: 'Initech' },
    ],
    value: [],
    onChange: fn(),
  },
} satisfies Meta<typeof CheckboxGroup>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NoneChecked: Story = {};
export const SomeChecked: Story = { args: { value: ['1', '3'] } };
export const Focus: Story = { play: focusFirst('input') };
export const Disabled: Story = { args: { disabled: true, value: ['2'] } };
export const GroupError: Story = { args: { error: '고객사를 하나 이상 고르세요.' } };
