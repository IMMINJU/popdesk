import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { LabelPicker } from './LabelPicker';

const COLORS = ['#808080', '#C0262D', '#B07A00', '#F2C14E', '#1F7A3A', '#0F766E', '#245FB0', '#6D28D9'];

const few = [
  { id: '1', name: '결제', color: '#245FB0' },
  { id: '2', name: '로그인', color: '#1F7A3A' },
  { id: '3', name: '모바일', color: '#F2C14E' },
];

/** 아홉: 여덟을 넘어 거르기 입력이 생긴다. */
const nine = Array.from({ length: 9 }, (_, i) => ({ id: String(11 + i), name: `라벨 ${String(i + 1)}`, color: COLORS[i % COLORS.length] ?? '#808080' }));

/** 스물다섯 가운데 스물이 붙은 문의. */
const many = Array.from({ length: 25 }, (_, i) => ({ id: String(101 + i), name: `라벨 ${String(i + 1)}`, color: COLORS[i % COLORS.length] ?? '#808080' }));

/**
 * 라벨 고르기: 단추 아래에 펼치는 체크 목록. 9개부터 거르기 입력, 한도에 차면 고르지 않은 줄을
 * 막고, 요청 중인 줄을 막고, 실패하면 목록 아래에 오류 줄. Esc와 바깥 누르기로 닫힌다.
 */
const meta = {
  component: LabelPicker,
  args: {
    label: '라벨',
    toggleLabel: '바꾸기',
    labels: few,
    selected: ['1'],
    onToggle: fn(),
    onOpenChange: fn(),
    limit: 20,
    limitText: '라벨은 20개까지 붙입니다.',
    filterLabel: '라벨 거르기',
    noMatchText: '맞는 라벨이 없습니다.',
    emptyText: '이 고객사에 라벨이 없습니다.',
  },
} satisfies Meta<typeof LabelPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 접힘. */
export const Folded: Story = {};
/** 펼침, 셋 가운데 하나 붙음. */
export const Open: Story = { args: { open: true } };
/** 고객사에 라벨이 없음(상담원). */
export const Empty: Story = { args: { open: true, labels: [], selected: [] } };
/** 고객사에 라벨이 없음(관리자): 만들러 가는 링크. */
export const EmptyWithAction: Story = {
  args: { open: true, labels: [], selected: [], emptyAction: <a href="/companies/ACME#labels">고객사 설정에서 만들기</a> },
};
/** 아홉: 위에 거르기 입력. */
export const WithFilter: Story = { args: { open: true, labels: nine, selected: ['11', '15'] } };
/** 20개 참: 체크하지 않은 줄을 막고 `limitText`를 보인다. */
export const Full: Story = { args: { open: true, labels: many, selected: many.slice(0, 20).map((l) => l.id) } };
/** 요청 중: 그 줄을 막는다. */
export const Busy: Story = { args: { open: true, selected: ['1', '2'], busyIds: ['2'] } };
/** 실패: 그 줄을 되돌리고 목록 아래에 오류 줄. */
export const Failed: Story = { args: { open: true, error: '라벨을 바꾸지 못했습니다. 다시 해 보세요.' } };
