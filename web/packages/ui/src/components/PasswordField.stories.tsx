import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { PasswordField } from './PasswordField';

const meta = {
  component: PasswordField,
  args: {
    label: '비밀번호',
    value: 'correct horse',
    onChange: fn(),
    onRevealedChange: fn(),
    autoComplete: 'current-password',
    showLabel: '비밀번호 보이기',
    hideLabel: '비밀번호 숨기기',
  },
} satisfies Meta<typeof PasswordField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Hidden: Story = {};
export const Revealed: Story = { args: { revealed: true } };
export const Focus: Story = { play: focusFirst('input') };
export const Error: Story = {
  args: { autoComplete: 'new-password', value: 'short', error: '10자 이상이어야 합니다.' },
};
export const Disabled: Story = { args: { disabled: true } };
export const ReadOnly: Story = { args: { readOnly: true } };
export const WithRules: Story = {
  args: {
    autoComplete: 'new-password',
    value: '',
    helpText: '10~72자, 공백만은 안 됩니다, 이메일과 달라야 합니다.',
  },
};
