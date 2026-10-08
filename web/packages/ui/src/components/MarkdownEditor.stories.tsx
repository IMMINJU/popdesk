import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { MarkdownEditor } from './MarkdownEditor';

const meta = {
  component: MarkdownEditor,
  args: {
    label: '댓글',
    value: '**확인했습니다.** 로그를 첨부해 주세요.',
    onChange: fn(),
    onModeChange: fn(),
    minHeight: 112,
  },
} satisfies Meta<typeof MarkdownEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Write: Story = { args: { mode: 'write' } };
export const Preview: Story = { args: { mode: 'preview' } };
export const Focus: Story = { play: focusFirst('textarea') };
export const Error: Story = { args: { value: '', error: '내용을 넣으세요.' } };
export const Disabled: Story = { args: { disabled: true } };
/** C-4 내부 메모 모드. */
export const Internal: Story = { args: { internal: true, label: '내부 메모', onSubmitShortcut: fn() } };
export const WithCount: Story = { args: { maxLength: 4000, showCount: true } };
/** P-4 문의 내용: 420px. */
export const IssueBody: Story = { args: { label: '내용', value: '', minHeight: 420, maxLength: 10000 } };
