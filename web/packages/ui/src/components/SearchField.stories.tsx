import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { SearchField } from './SearchField';

/**
 * 3번 묶음의 껍데기다. 이름표가 붙은 검색 입력만 그리고 아직 동작하지 않는다.
 * 입력을 멈춘 뒤와 Enter에 `onSearch`를 부르는 것, "검색어 지우기" 버튼은 C-9가 채운다.
 */
const meta = {
  component: SearchField,
  args: { label: '문의 검색', value: '', onSearch: fn() },
} satisfies Meta<typeof SearchField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
/** 값이 있음. 지우기 버튼은 아직 없다. */
export const WithValue: Story = { args: { value: '첨부' } };
export const WithHint: Story = { args: { hint: '제목과 내용에서 찾습니다' } };
export const Disabled: Story = { args: { value: '첨부', disabled: true } };
