import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { RadioGroup } from './RadioGroup';

type Role = 'CUSTOMER' | 'AGENT' | 'ADMIN';

const meta = {
  component: RadioGroup<Role>,
  args: {
    legend: '역할',
    options: [
      { value: 'CUSTOMER', label: '고객' },
      { value: 'AGENT', label: '상담원' },
      { value: 'ADMIN', label: '관리자' },
    ],
    value: null,
    onChange: fn(),
  },
} satisfies Meta<typeof RadioGroup<Role>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NoneSelected: Story = {};
export const Selected: Story = { args: { value: 'AGENT' } };
export const Focus: Story = { play: focusFirst('input') };
export const Disabled: Story = { args: { disabled: true, value: 'CUSTOMER' } };
export const GroupError: Story = { args: { error: '역할을 고르세요.' } };

/** C-4 공개·내부 탭: looks like tabs, stays a radiogroup. */
export const Tabs: StoryObj<typeof RadioGroup<'public' | 'internal'>> = {
  render: (args) => <RadioGroup {...args} />,
  args: {
    legend: '댓글 종류',
    legendHidden: true,
    appearance: 'tabs',
    options: [
      { value: 'public', label: '공개 답변' },
      { value: 'internal', label: '내부 메모' },
    ],
    value: 'public',
    onChange: fn(),
  },
};
