import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { fn } from 'storybook/test';
import { SearchField, type SearchFieldProps } from './SearchField';

/** Keeps the searched text the way a screen keeps it in the address, and shows it under the box. */
function Searched(props: SearchFieldProps) {
  const [value, setValue] = useState(props.value);
  return (
    <>
      <SearchField
        {...props}
        value={value}
        onSearch={(next) => {
          setValue(next);
          props.onSearch(next);
        }}
      />
      <p className="pd-muted">찾는 글자: {value === '' ? '없음' : value}</p>
    </>
  );
}

/**
 * 검색 입력. 입력을 멈추고 `delayMs`(기본 300ms)가 지나면 `onSearch`를 부르고, Enter는 바로 부른다.
 * 값이 있을 때만 "검색어 지우기" 버튼이 있다.
 */
const meta = {
  component: SearchField,
  args: { label: '문의 검색', value: '', onSearch: fn() },
  render: (args) => <Searched {...args} />,
} satisfies Meta<typeof SearchField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
/** 값이 있음. 오른쪽에 "검색어 지우기" 버튼이 있다. */
export const WithValue: Story = { args: { value: '첨부' } };
export const WithHint: Story = { args: { hint: '제목과 내용에서 찾습니다' } };
/** 입력을 멈추고 1초 뒤에 찾는다. */
export const SlowDelay: Story = { args: { delayMs: 1000 } };
export const Disabled: Story = { args: { value: '첨부', disabled: true } };
