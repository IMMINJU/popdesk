import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, waitFor, within } from 'storybook/test';
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

// 이미지 (5-attachments screens.md 2절·4절). The stories draw pictures made here, not uploaded ones.
const IMAGE_ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const picture = (width: number, height: number) =>
  `data:image/svg+xml,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${String(width)}" height="${String(height)}"><rect width="100%" height="100%" fill="#d1fae5"/><path d="M0 0L${String(width)} ${String(height)}M${String(width)} 0L0 ${String(height)}" stroke="#047857" stroke-width="4"/></svg>`,
  )}`;
const IMAGE_SOURCE = `로그인하면 이 화면에서 멈춥니다.\n\n![오류 화면](attachment:${IMAGE_ID})\n\n다시 해 봐도 같습니다.`;

/** 읽힘: an image narrower than the text keeps its size. */
export const Image: Story = { args: { source: IMAGE_SOURCE, imageSrc: () => picture(320, 180) } };
/** 읽지 못함: the description and "이미지를 볼 수 없습니다" in a bordered line, no broken picture. */
export const ImageUnreadable: Story = { args: { source: IMAGE_SOURCE, imageSrc: () => 'data:image/png;base64,AAAA' } };
/**
 * 큰 이미지 (S5-AC-53): 2000px wide, drawn inside the width of the text. The first story that
 * asserts "no sideways overflow" in `play`; it fails in the Storybook panel when the page scrolls sideways.
 */
export const LargeImage: Story = {
  args: { source: IMAGE_SOURCE, imageSrc: () => picture(2000, 900) },
  play: async ({ canvasElement }) => {
    const img = await within(canvasElement).findByRole<HTMLImageElement>('img', { name: '오류 화면' });
    await waitFor(() => expect(img.naturalWidth).toBe(2000));
    const view = canvasElement.querySelector('.pd-markdown') as HTMLElement;
    await expect(view.scrollWidth).toBeLessThanOrEqual(view.clientWidth);
    await expect(img.getBoundingClientRect().width).toBeLessThanOrEqual(view.clientWidth);
    const page = document.documentElement;
    await expect(page.scrollWidth).toBeLessThanOrEqual(page.clientWidth);
  },
};
