import type { Meta, StoryObj } from '@storybook/react-vite';
import { LabelChip } from './LabelChip';

/**
 * 6a 묶음의 껍데기다. 이름을 글자로, 색을 바탕으로만 그린다.
 * 글자색(`labelTextColor`), 테두리, 크기는 C-9가 채운다.
 */
const meta = {
  component: LabelChip,
  args: { name: '결제', color: '#245FB0' },
} satisfies Meta<typeof LabelChip>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 밝은 바탕(노랑): 검은 글자. */
export const LightColor: Story = { args: { name: '긴급 확인', color: '#F2C14E' } };
/** 어두운 바탕(파랑): 흰 글자. */
export const DarkColor: Story = { args: { name: '결제', color: '#245FB0' } };
/** 기본 회색 `#808080`. */
export const Gray: Story = { args: { name: '기타', color: '#808080' } };
/** 30자(한도) 이름. */
export const LongName: Story = { args: { name: '모바일 앱 로그인과 결제 연동에서 자주 나는 오류 모음', color: '#1F7A3A' } };
/** 어둡게: 어두운 바탕(보라). 바탕이 화면과 비슷해 테두리가 칩을 가른다. */
export const DarkThemeDarkColor: Story = { args: { name: '보안', color: '#6D28D9' }, globals: { theme: 'dark' } };
/** 어둡게: 밝은 바탕(노랑). */
export const DarkThemeLightColor: Story = { args: { name: '긴급 확인', color: '#F2C14E' }, globals: { theme: 'dark' } };
/** 어둡게: 기본 회색. */
export const DarkThemeGray: Story = { args: { name: '기타', color: '#808080' }, globals: { theme: 'dark' } };
