import type { Meta, StoryObj } from '@storybook/react-vite';
import { AuthBrand } from './AuthBrand';

const logo = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';

const meta = {
  component: AuthBrand,
  args: { displayName: 'popdesk' },
} satisfies Meta<typeof AuthBrand>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 로고 없음: the display name alone, as on the login screens now. */
export const NoLogo: Story = {};
/** 로고 있음: the logo above the name (C-9 draws it). */
export const WithLogo: Story = { args: { displayName: 'Acme 지원', logo: { light: logo, dark: logo } } };
