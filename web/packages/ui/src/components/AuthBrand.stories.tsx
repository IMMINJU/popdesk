import type { Meta, StoryObj } from '@storybook/react-vite';
import { AuthBrand } from './AuthBrand';

/** A plain logo `width`×`height` as an SVG `data:` URL: a band of `color` with a darker mark. */
function logoImage(width: number, height: number, color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${String(width)}" height="${String(height)}"><rect width="100%" height="100%" fill="${color}"/><rect x="8" y="8" width="${String(Math.max(width / 3, 8))}" height="${String(height - 16)}" fill="#1F1F1F"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** A wide logo (512×64) and a square one (128×128), the sizes of screens.md 2절 폭. */
const wide = logoImage(512, 64, '#6D28D9');
const square = logoImage(128, 128, '#D9D4FF');

const meta = {
  component: AuthBrand,
  args: { displayName: 'popdesk' },
} satisfies Meta<typeof AuthBrand>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 로고 없음: the display name alone, as on the login screens now. */
export const NoLogo: Story = {};
/** 로고 있음: the logo of the theme above the name (wide in light, square in dark). */
export const WithLogo: Story = { args: { displayName: 'Acme 지원', logo: { light: wide, dark: square } } };
/** 긴 이름(50자): it wraps between words and stays inside the panel. */
export const LongName: Story = {
  args: { displayName: '에이크미 고객 지원 센터 서울 본사 상담 콘솔 운영팀 공식 브랜드 표시 이름 오십 자 끝냄', logo: { light: wide, dark: wide } },
};
