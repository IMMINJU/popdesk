import type { Meta, StoryObj } from '@storybook/react-vite';
import type { LabelItem } from '../types';
import { LabelChips } from './LabelChips';

const six: LabelItem[] = [
  { id: '1', name: '결제', color: '#245FB0' },
  { id: '2', name: '로그인', color: '#1F7A3A' },
  { id: '3', name: '모바일', color: '#F2C14E' },
  { id: '4', name: '보안', color: '#6D28D9' },
  { id: '5', name: '기타', color: '#808080' },
  { id: '6', name: '장애', color: '#C0262D' },
];

const long: LabelItem[] = [
  { id: '11', name: '모바일 앱 로그인과 결제 연동에서 자주 나는 오류 모음', color: '#0F766E' },
  { id: '12', name: '고객사 관리자 계정의 권한과 초대 메일이 늦게 오는 일', color: '#B07A00' },
  { id: '13', name: '첨부 파일 올리기와 내려받기에서 생기는 문제를 모은 것', color: '#245FB0' },
  { id: '14', name: '알림 메일이 오지 않거나 늦게 오는 문의를 모아 둔 곳', color: '#6D28D9' },
];

/**
 * 6a 묶음의 껍데기다. 칩을 다 그린다.
 * `max`(기본 3)에서 자르기와 "+n"(화면 읽기와 `title`에 가린 이름)은 C-9가 채운다.
 */
const meta = {
  component: LabelChips,
  args: {
    labels: six.slice(0, 2),
    moreText: (hidden: readonly LabelItem[]) => `라벨 ${String(hidden.length)}개 더: ${hidden.map((l) => l.name).join(', ')}`,
  },
} satisfies Meta<typeof LabelChips>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 라벨 없음: 아무것도 그리지 않는다. */
export const None: Story = { args: { labels: [] } };
/** 둘. */
export const Two: Story = {};
/** 셋: 한도와 같아 "+n"이 없다. */
export const Three: Story = { args: { labels: six.slice(0, 3) } };
/** 여섯: 칩 셋과 "+3". 껍데기라 아직 여섯을 다 보인다. */
export const Six: Story = { args: { labels: six } };
/** 30자 이름 넷: 칩 셋과 "+1". 껍데기라 아직 넷을 다 보인다. */
export const LongNames: Story = { args: { labels: long } };
