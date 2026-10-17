import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { LogoField } from './LogoField';

/** A plain logo `width`×`height` as an SVG `data:` URL: a band of `color` with a darker mark. */
function logoImage(width: number, height: number, color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${String(width)}" height="${String(height)}"><rect width="100%" height="100%" fill="${color}"/><rect x="8" y="8" width="${String(Math.max(width / 3, 8))}" height="${String(height - 16)}" fill="#1F1F1F"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** A wide logo (512×64) and a square one (128×128), the sizes of screens.md 2절 폭. */
const wide = logoImage(512, 64, '#6D28D9');
const square = logoImage(128, 128, '#D9D4FF');

const meta = {
  component: LogoField,
  args: {
    theme: 'dark',
    label: '어두운 테마 로고',
    logo: null,
    helpText: 'PNG, JPEG, WebP, 512KB까지. 가로 512px, 세로 128px 안으로 줄여 저장합니다. 하나만 올리면 두 테마에 같이 씁니다.',
    accept: 'image/png,image/jpeg,image/webp',
    onUpload: fn(),
    onRemove: fn(),
    uploadLabel: '올리기',
    changeLabel: '바꾸기',
    removeLabel: '지우기',
  },
} satisfies Meta<typeof LogoField>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 없음, 다른 테마도 없음: "로고 없음" and 올리기. */
export const NoLogo: Story = {};
/** 없음, 다른 테마에 있음: the light theme's logo, faded, and "없음. 밝은 테마 로고를 같이 씁니다". */
export const UsesOtherTheme: Story = { args: { fallback: { url: wide, from: 'light' } } };
/** 있음: the logo on this theme's background, "가로 × 세로 px", 바꾸기 and 지우기. */
export const WithLogo: Story = { args: { logo: { url: square, width: 128, height: 128 } } };
/** 올리는 중: "올리는 중…", `aria-busy`, the input blocked with `aria-disabled`. */
export const Uploading: Story = { args: { logo: { url: square, width: 128, height: 128 }, uploading: true } };
/** 413, `UNSUPPORTED_IMAGE`, `IMAGE_TOO_LARGE`: reason text under the field, the logo before it stays. */
export const Failed: Story = {
  args: { logo: { url: wide, width: 512, height: 64 }, error: '로고가 512KB를 넘습니다. 더 작은 파일을 올리세요.' },
};
