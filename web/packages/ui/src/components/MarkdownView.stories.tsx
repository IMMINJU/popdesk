import type { Meta, StoryObj } from '@storybook/react-vite';
import { MarkdownView } from './MarkdownView';

const meta = {
  component: MarkdownView,
  args: { source: '# 제목\n\n**굵게**, _기울임_, ~~취소선~~\n\n- 하나\n- 둘\n\n> 인용' },
} satisfies Meta<typeof MarkdownView>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Basic: Story = {};
/** Links open in a new tab with rel="noopener noreferrer" (COM-6). */
export const Links: Story = { args: { source: '[운영 문서](https://example.com/docs)를 보세요.' } };
/** Raw HTML is not rendered (allow list). */
export const RawHtml: Story = { args: { source: '<script>alert(1)</script> <b>굵게?</b>' } };
export const LongCode: Story = {
  args: { source: '```\n' + 'const line = "' + 'x'.repeat(200) + '";\n```' },
};
