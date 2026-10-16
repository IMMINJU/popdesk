import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { TextField } from './TextField';

const meta = {
  component: TextField,
  args: { label: '이메일', value: '', onChange: fn(), type: 'email', autoComplete: 'username' },
} satisfies Meta<typeof TextField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Focus: Story = { play: focusFirst('input') };
export const Error: Story = { args: { value: 'kim@', error: '이메일 모양이 아닙니다.' } };
export const Disabled: Story = { args: { value: 'kim@example.com', disabled: true } };
export const ReadOnly: Story = { args: { value: 'kim@example.com', readOnly: true } };
export const WithHelp: Story = {
  args: { label: '코드', type: 'text', value: 'ACME', helpText: '나중에 바꿀 수 없습니다.' },
};
export const WithCount: Story = {
  args: { label: '제목', type: 'text', value: '첨부가 안 올라갑니다', maxLength: 200, showCount: true },
};
/** 날짜: the browser's date field (C-12 기간 필터). The value is `YYYY-MM-DD`. */
export const Date: Story = {
  args: { label: '부터', type: 'date', value: '2026-10-01', autoComplete: 'off' },
};
/** 마감일 (6a 묶음): `min`으로 오늘보다 이전 날은 고르지 못하게 한다(힌트, 판정은 서버). */
export const DueDate: Story = {
  args: { label: '마감일', type: 'date', value: '2026-10-20', min: '2026-10-14', autoComplete: 'off' },
};
/** 마감일 400: 입력 아래 오류. */
export const DueDatePast: Story = {
  args: { label: '마감일', type: 'date', value: '2026-10-01', min: '2026-10-14', autoComplete: 'off', error: '오늘이나 그 뒤의 날짜를 고르세요.' },
};
/** 시각 (C-13 업무 시간): the browser's time field. The value is `HH:MM`. */
export const TimeEmpty: Story = {
  args: { label: '월요일 시작', type: 'time', value: '', autoComplete: 'off' },
};
export const Time: Story = {
  args: { label: '월요일 시작', type: 'time', value: '09:00', autoComplete: 'off' },
};
export const TimeError: Story = {
  args: { label: '월요일 끝', type: 'time', value: '08:00', autoComplete: 'off', error: '끝 시각은 시작 시각보다 늦어야 합니다.' },
};
export const TimeWithHelp: Story = {
  args: { label: '월요일 끝', type: 'time', value: '18:00', autoComplete: 'off', helpText: '자정까지는 24:00' },
};
/** 숫자 (C-13 목표 분): `min`·`max` are hints, the server decides. */
export const NumberEmpty: Story = {
  args: { label: '높음 첫 응답(분)', type: 'number', value: '', min: 1, max: 525600, autoComplete: 'off' },
};
export const NumberValue: Story = {
  args: { label: '높음 첫 응답(분)', type: 'number', value: '480', min: 1, max: 525600, autoComplete: 'off' },
};
export const NumberError: Story = {
  args: { label: '높음 첫 응답(분)', type: 'number', value: '0', min: 1, max: 525600, autoComplete: 'off', error: '1분에서 525,600분 사이로 정합니다.' },
};
export const NumberWithHelp: Story = {
  args: { label: '높음 해결(분)', type: 'number', value: '3120', min: 1, max: 525600, autoComplete: 'off', helpText: '= 2일 4시간' },
};
