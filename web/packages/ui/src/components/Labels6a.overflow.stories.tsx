// 6a 묶음의 공용 컴포넌트 넷을 360·820·1280 폭, 밝게·어둡게에서 그리고, play에서 페이지와 부품이
// 가로로 넘치지 않는지 본다(screens.md 5·6절, S6A-AC-85, S6A-AC-91). 가장 넓은 꼴로 그린다:
// 30자 이름, 견본 여덟, 열린 고르기(거르기 입력, 한도, 오류).
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactElement } from 'react';
import { at, expectFits, expectPageFits, namedPart, settled, textOf, waitFor, type Theme, type Width } from '../__tests__/overflow';
import type { LabelItem } from '../types';
import { DueDatePicker } from './DueDatePicker';
import { LabelChip } from './LabelChip';
import { LabelChips } from './LabelChips';
import { LabelPicker } from './LabelPicker';

const meta = { title: 'Overflow/6a 라벨과 마감일 부품' } satisfies Meta;
export default meta;
type Story = StoryObj<typeof meta>;

const LONG = '고객사 결제 모듈 장애 대응 우선 처리 필요한 건 모음';
const SWATCHES = ['#808080', '#C0262D', '#B07A00', '#F2C14E', '#1F7A3A', '#0F766E', '#245FB0', '#6D28D9'];
const noop = () => undefined;

const LABELS: LabelItem[] = Array.from({ length: 25 }, (_, i) => ({
  id: String(100 + i),
  name: i % 3 === 0 ? `${LONG.slice(0, 27)} ${String(i).padStart(2, '0')}` : `라벨 ${String(i + 1)}`,
  color: SWATCHES[i % SWATCHES.length] ?? '#808080',
}));
const moreText = (hidden: readonly LabelItem[]) => `라벨 ${String(hidden.length)}개 더: ${hidden.map((l) => l.name).join(', ')}`;

function chips(): ReactElement {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {SWATCHES.map((color) => (
        <LabelChip key={color} name={`라벨 ${color}`} color={color} />
      ))}
      <LabelChip name={LONG} color="#245FB0" />
    </div>
  );
}

function chipRow(): ReactElement {
  return <LabelChips labels={LABELS.filter((_, i) => i % 3 === 0).slice(0, 6)} moreText={moreText} />;
}

function picker(): ReactElement {
  return (
    <LabelPicker
      label="라벨"
      toggleLabel="바꾸기"
      labels={LABELS}
      selected={LABELS.slice(0, 20).map((l) => l.id)}
      onToggle={noop}
      busyIds={['101']}
      limit={20}
      limitText="라벨은 20개까지 붙입니다."
      open
      onOpenChange={noop}
      filterLabel="라벨 거르기"
      noMatchText="맞는 라벨이 없습니다."
      emptyText="이 고객사에 라벨이 없습니다."
      error="라벨을 바꾸지 못했습니다. 다시 해 보세요."
    />
  );
}

function duePicker(): ReactElement {
  return (
    <DueDatePicker
      label="마감일"
      toggleLabel="바꾸기"
      value="2026-10-20"
      min="2026-10-14"
      onSubmit={noop}
      onClear={noop}
      submitLabel="정하기"
      clearLabel="지우기"
      error="오늘이나 그 뒤의 날짜를 고르세요."
      open
      onOpenChange={noop}
    />
  );
}

type Part = (canvas: HTMLElement) => Promise<{ el: Element; what: string }[]>;

const chipParts: Part = async (canvas) => {
  const long = await waitFor(() => Array.from(canvas.querySelectorAll('span')).find((s) => textOf(s) === LONG), '30자 이름의 칩');
  // The row the story draws: a chip may wrap or cut its name, but no chip may push the row wider.
  const right = long.getBoundingClientRect().right;
  if (right > document.documentElement.clientWidth) throw new Error(`30자 이름의 칩이 화면 밖으로 나간다 (right ${String(right)})`);
  return [{ el: canvas.firstElementChild ?? canvas, what: '칩 여럿의 줄' }];
};

const chipsParts: Part = async (canvas) => {
  const more = await waitFor(() => canvas.querySelector('[title]'), '"+n"');
  return [{ el: more.parentElement ?? more, what: '칩 줄' }];
};

const pickerParts: Part = async (canvas) => {
  const group = await waitFor(() => namedPart(canvas, '라벨'), '열린 라벨 고르기(group "라벨")');
  return [{ el: group, what: '라벨 고르기의 목록' }];
};

const dueParts: Part = async (canvas) => {
  const input = await waitFor(() => canvas.querySelector('input[type="date"]'), '열린 마감일 고르기의 날짜 입력');
  return [{ el: input.closest('[id$="-panel"]') ?? input.parentElement ?? input, what: '마감일 고르기' }];
};

function story(width: Width, theme: Theme, render: () => ReactElement, parts: Part): Story {
  return {
    ...at(width, theme),
    render,
    play: async ({ canvasElement }) => {
      const found = await parts(canvasElement);
      await settled();
      expectPageFits(width);
      for (const { el, what } of found) expectFits(el, what);
    },
  };
}

export const LabelChip360Light = story(360, 'light', chips, chipParts);
export const LabelChip360Dark = story(360, 'dark', chips, chipParts);
export const LabelChip820Light = story(820, 'light', chips, chipParts);
export const LabelChip820Dark = story(820, 'dark', chips, chipParts);
export const LabelChip1280Light = story(1280, 'light', chips, chipParts);
export const LabelChip1280Dark = story(1280, 'dark', chips, chipParts);

export const LabelChips360Light = story(360, 'light', chipRow, chipsParts);
export const LabelChips360Dark = story(360, 'dark', chipRow, chipsParts);
export const LabelChips820Light = story(820, 'light', chipRow, chipsParts);
export const LabelChips820Dark = story(820, 'dark', chipRow, chipsParts);
export const LabelChips1280Light = story(1280, 'light', chipRow, chipsParts);
export const LabelChips1280Dark = story(1280, 'dark', chipRow, chipsParts);

export const LabelPicker360Light = story(360, 'light', picker, pickerParts);
export const LabelPicker360Dark = story(360, 'dark', picker, pickerParts);
export const LabelPicker820Light = story(820, 'light', picker, pickerParts);
export const LabelPicker820Dark = story(820, 'dark', picker, pickerParts);
export const LabelPicker1280Light = story(1280, 'light', picker, pickerParts);
export const LabelPicker1280Dark = story(1280, 'dark', picker, pickerParts);

export const DueDatePicker360Light = story(360, 'light', duePicker, dueParts);
export const DueDatePicker360Dark = story(360, 'dark', duePicker, dueParts);
export const DueDatePicker820Light = story(820, 'light', duePicker, dueParts);
export const DueDatePicker820Dark = story(820, 'dark', duePicker, dueParts);
export const DueDatePicker1280Light = story(1280, 'light', duePicker, dueParts);
export const DueDatePicker1280Dark = story(1280, 'dark', duePicker, dueParts);
