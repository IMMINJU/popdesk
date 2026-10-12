import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { fn } from 'storybook/test';
import { PeoplePicker } from './PeoplePicker';

const people = [
  { id: '11', label: '김상담 (상담원)' },
  { id: '12', label: '이관리 (관리자)' },
  { id: '13', label: '최상담 (상담원)' },
  { id: '31', label: '박고객 (올린 고객)' },
];

/** 열둘: 여덟을 넘으면 후보 구역만 스크롤한다. */
const many = Array.from({ length: 12 }, (_, i) => ({ id: String(101 + i), label: `상담원 ${String(i + 1)} (상담원)` }));

/**
 * 여러 사람 고르기(4번 묶음 "알릴 사람"). `open`을 주지 않은 story는 버튼으로 펼치고 접는다.
 * 고른 사람은 story가 쥐고 있어 상자를 누르면 바뀐다. 요약 글은 화면이 만들어 주는 것이라 그대로다.
 */
const meta = {
  component: PeoplePicker,
  args: {
    label: '알릴 사람',
    toggleLabel: '알릴 사람 고르기',
    candidates: people,
    value: [],
    onChange: fn(),
    onOpenChange: fn(),
    summary: '알릴 사람 없음',
    max: 10,
    maxText: '10명까지 고를 수 있습니다',
    loadingText: '불러오는 중…',
    failedText: '후보를 불러오지 못했습니다',
    retryLabel: '다시 불러오기',
    onRetry: fn(),
    emptyText: '알릴 수 있는 사람이 없습니다',
  },
  render: function Picked(args) {
    const [value, setValue] = useState(args.value);
    return (
      <PeoplePicker
        {...args}
        value={value}
        onChange={(next) => {
          setValue(next);
          args.onChange(next);
        }}
      />
    );
  },
} satisfies Meta<typeof PeoplePicker>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 접힘, 아무도 고르지 않음. */
export const FoldedNone: Story = {};
/** 접힘, 둘 고름. */
export const FoldedTwo: Story = { args: { value: ['11', '12'], summary: '알릴 사람: 김상담, 이관리' } };
/** 펼침(`open`을 준 story는 펼친 채로 있다). */
export const Open: Story = { args: { open: true, value: ['11'], summary: '알릴 사람: 김상담' } };
/** 후보를 받는 중. */
export const Loading: Story = { args: { open: true, candidates: [], status: 'loading' } };
/** 후보 없음. */
export const NoCandidates: Story = { args: { open: true, candidates: [] } };
/** 후보 받기 실패. */
export const Failed: Story = { args: { open: true, candidates: [], status: 'failed' } };
/** 10명이 참: 나머지 상자를 막고 `maxText`를 보인다. 후보가 열둘이라 후보 구역만 스크롤한다. */
export const Full: Story = {
  args: {
    open: true,
    candidates: many,
    value: many.slice(0, 10).map((p) => p.id),
    summary: `알릴 사람: ${many
      .slice(0, 10)
      .map((p) => p.label.replace(' (상담원)', ''))
      .join(', ')}`,
  },
};
/** 댓글 종류를 내부 메모로 바꿔 올린 고객이 빠졌을 때의 알림 한 줄. */
export const WithNotice: Story = {
  args: {
    value: ['11'],
    summary: '알릴 사람: 김상담',
    notice: '내부 메모에서는 고객에게 알릴 수 없어 박고객 님을 뺐습니다.',
  },
};
