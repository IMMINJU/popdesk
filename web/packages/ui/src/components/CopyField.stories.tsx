import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { CopyField } from './CopyField';

const meta = {
  component: CopyField,
  args: {
    label: '초대 링크',
    // Story value only. Not a real link format; the contract decides it.
    value: 'https://portal.example.com/invite#token=EXAMPLE',
    onCopy: fn(),
    copyLabel: '복사',
    helpText: '이 링크는 지금만 보입니다.',
  },
} satisfies Meta<typeof CopyField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Copied: Story = { args: { status: 'copied' } };
export const ClipboardUnavailable: Story = { args: { status: 'unavailable', helpText: 'Ctrl+C로 복사하세요' } };
