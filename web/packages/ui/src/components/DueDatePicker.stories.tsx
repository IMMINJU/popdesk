import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { DueDatePicker } from './DueDatePicker';

/**
 * 마감일 고르기: 단추 아래에 날짜 입력(`TextField`, `min`은 힌트), "정하기", 마감일이 있으면
 * "지우기". 요청 중에는 막고, 서버 400은 입력 아래 오류다. Esc와 바깥 누르기로 닫힌다.
 */
const meta = {
  component: DueDatePicker,
  args: {
    label: '마감일',
    toggleLabel: '바꾸기',
    value: null,
    min: '2026-10-14',
    onSubmit: fn(),
    onClear: fn(),
    onOpenChange: fn(),
    submitLabel: '정하기',
    clearLabel: '지우기',
  },
} satisfies Meta<typeof DueDatePicker>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 마감일 없음, 접힘. */
export const NoValue: Story = {};
/** 마감일 있음, 접힘. */
export const WithValue: Story = { args: { value: '2026-10-20' } };
/** 펼침, 마감일 있음: 날짜 입력, "정하기", "지우기". */
export const Open: Story = { args: { open: true, value: '2026-10-20' } };
/** 펼침, 마감일 없음: "지우기"가 없다. */
export const OpenNoValue: Story = { args: { open: true } };
/** 요청 중: 입력과 단추를 막는다. */
export const Busy: Story = { args: { open: true, value: '2026-10-20', busy: true } };
/** 서버 400: 입력 아래 오류. */
export const Failed: Story = { args: { open: true, value: '2026-10-20', error: '오늘이나 그 뒤의 날짜를 고르세요.' } };
