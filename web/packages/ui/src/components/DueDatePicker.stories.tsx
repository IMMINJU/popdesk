import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { DueDatePicker } from './DueDatePicker';

/**
 * 6a 묶음의 껍데기다. 접힌 단추 하나만 그리고 아직 동작하지 않는다.
 * 펼치기, 날짜 입력(`TextField`, `min`), "정하기"와 "지우기", 요청 중 막기, 오류,
 * Esc와 바깥 누르기로 닫기는 C-9가 채운다.
 * 그래서 아래 story 가운데 펼친 것들은 지금은 접힌 단추로만 보인다.
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
/** 펼침, 마감일 있음: 날짜 입력, "정하기", "지우기". 껍데기라 아직 접힌 단추만 보인다. */
export const Open: Story = { args: { open: true, value: '2026-10-20' } };
/** 펼침, 마감일 없음: "지우기"가 없다. 껍데기라 아직 접힌 단추만 보인다. */
export const OpenNoValue: Story = { args: { open: true } };
/** 요청 중: 입력과 단추를 막는다. 껍데기라 아직 접힌 단추만 보인다. */
export const Busy: Story = { args: { open: true, value: '2026-10-20', busy: true } };
/** 서버 400: 입력 아래 오류. 껍데기라 아직 접힌 단추만 보인다. */
export const Failed: Story = { args: { open: true, value: '2026-10-20', error: '오늘이나 그 뒤의 날짜를 고르세요.' } };
