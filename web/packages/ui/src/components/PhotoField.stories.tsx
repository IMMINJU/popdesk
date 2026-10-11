import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { PhotoField } from './PhotoField';

const photo = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';

const meta = {
  component: PhotoField,
  args: {
    name: '김하나',
    helpText: 'PNG·JPEG·WebP, 2MB까지',
    accept: 'image/png,image/jpeg,image/webp',
    onUpload: fn(),
    onRemove: fn(),
    uploadLabel: '사진 올리기',
    changeLabel: '바꾸기',
    removeLabel: '지우기',
  },
} satisfies Meta<typeof PhotoField>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 사진 없음: initials and 사진 올리기. */
export const NoPhoto: Story = {};
/** 사진 있음: the photo, 바꾸기 and 지우기. */
export const WithPhoto: Story = { args: { photoUrl: photo } };
/** 올리는 중: buttons blocked, spinner. */
export const Uploading: Story = { args: { uploading: true } };
/** 413, `UNSUPPORTED_IMAGE`, `IMAGE_TOO_LARGE`: reason text, the photo before it stays. */
export const Failed: Story = { args: { photoUrl: photo, error: '2MB를 넘습니다. 더 작은 사진을 올리세요.' } };
