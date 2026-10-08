import type { Meta, StoryObj } from '@storybook/react-vite';
import { DataTable, type DataTableColumn } from './DataTable';

interface ProjectRow {
  id: string;
  code: string;
  name: string;
  company: string;
}

const columns: DataTableColumn<ProjectRow>[] = [
  { key: 'code', header: '코드', cell: (r) => r.code, sticky: true },
  { key: 'name', header: '이름', cell: (r) => r.name },
  { key: 'company', header: '회사명', cell: (r) => r.company },
];

const rows: ProjectRow[] = [
  { id: '1', code: 'ACME', name: 'Acme', company: 'Acme Corp.' },
  { id: '2', code: 'GLOBEX', name: 'Globex', company: 'Globex Inc.' },
];

const meta = {
  component: DataTable<ProjectRow>,
  args: {
    caption: '고객사 목록, 2건',
    columns,
    rows,
    rowKey: (r) => r.id,
    empty: '첫 고객사를 만드세요.',
    error: '서버에 연결하지 못했습니다.',
  },
} satisfies Meta<typeof DataTable<ProjectRow>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loading: Story = { args: { status: 'loading', skeletonRows: 10 } };
export const Empty: Story = { args: { rows: [] } };
export const WithData: Story = {};
export const Error: Story = { args: { status: 'error' } };
/** 열 고정 and 가로 넘김: the code column stays while the rest scrolls. */
export const StickyColumn: Story = {
  args: {
    columns: [
      ...columns,
      ...Array.from({ length: 6 }, (_, i) => ({ key: `extra${String(i)}`, header: `열 ${String(i + 4)}`, cell: () => '…' })),
    ],
  },
};
export const Compact: Story = { args: { density: 'compact' } };
export const Comfortable: Story = { args: { density: 'comfortable' } };
