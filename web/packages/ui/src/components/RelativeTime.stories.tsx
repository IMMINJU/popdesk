import type { Meta, StoryObj } from '@storybook/react-vite';
import { focusFirst } from '../storybook/states';
import { RelativeTime } from './RelativeTime';

const now = new Date('2026-10-09T12:00:00Z');

const meta = {
  component: RelativeTime,
  args: { dateTime: '2026-10-09T11:59:30Z', now, locale: 'ko' },
} satisfies Meta<typeof RelativeTime>;

export default meta;
type Story = StoryObj<typeof meta>;

export const JustNow: Story = {};
export const HoursAgo: Story = { args: { dateTime: '2026-10-09T09:00:00Z' } };
export const DaysAgo: Story = { args: { dateTime: '2026-10-02T09:00:00Z' } };
/** 절대 시각 on hover and focus. */
export const Focus: Story = { args: { dateTime: '2026-10-09T09:00:00Z' }, play: focusFirst('time') };
