// b1 묶음 screens.md 4절 막힌 단추(B1-AC-05). 이름의 .overflow는 테스트 쪽이 쓰는 story의 꼴이다(scripts/ci/lockrules.py). 다섯 변형마다 `blocked` 단추와 `disabled` 단추를 나란히 두고, play에서 계산된
// cursor, opacity, background-color, color가 같은지(커서 not-allowed, 불투명도 1), `blocked` 단추가 aria-disabled="true"이고
// Tab으로 초점을 받는지 본다. 마우스를 올려도 `blocked` 단추의 바탕이 바뀌지 않는지는 story 실행기의 실제 마우스(vitest/browser)로
// 본다. `pending` 단추는 커서 progress로 남는지 본다.
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent } from 'storybook/test';
import { animationsDone } from '../__tests__/overflow';
import { Button, type ButtonVariant } from './Button';

const meta = {
  title: 'Button/b1 막힌 단추',
  component: Button,
  args: { children: '올리기' },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

type Look = 'cursor' | 'opacity' | 'background-color' | 'color';

function looks(el: Element): Record<Look, string> {
  const style = getComputedStyle(el);
  return { cursor: style.cursor, opacity: style.opacity, 'background-color': style.backgroundColor, color: style.color };
}

/** Moves the real mouse of the story runner onto `el`; false where there is none (Storybook in a browser by hand). */
async function realHover(el: Element): Promise<boolean> {
  try {
    const browser = await import('vitest/browser');
    await browser.userEvent.hover(el);
    return true;
  } catch {
    return false;
  }
}

function blockedStory(variant: ButtonVariant): Story {
  return {
    render: () => (
      <div style={{ display: 'flex', gap: 16, padding: 16 }}>
        <Button variant={variant} blocked>
          막힘
        </Button>
        <Button variant={variant} disabled>
          못 누름
        </Button>
      </div>
    ),
    play: async ({ canvasElement }) => {
      const blocked = canvasElement.querySelector<HTMLButtonElement>('button[aria-disabled="true"]:not([data-pending])');
      const disabled = canvasElement.querySelector<HTMLButtonElement>('button:disabled');
      await expect(blocked, 'blocked 단추').not.toBeNull();
      await expect(disabled, 'disabled 단추').not.toBeNull();
      if (!blocked || !disabled) return;
      await animationsDone();

      await expect(blocked.getAttribute('aria-disabled'), 'aria-disabled').toBe('true');
      await expect(blocked.disabled, 'blocked 단추는 disabled가 아니다').toBe(false);
      await expect(looks(blocked), `${variant}: blocked와 disabled의 모양`).toEqual(looks(disabled));
      await expect(looks(blocked).cursor, '커서').toBe('not-allowed');
      await expect(looks(blocked).opacity, '불투명도').toBe('1');

      (document.activeElement as HTMLElement | null)?.blur();
      await userEvent.tab();
      await expect(document.activeElement, 'Tab으로 blocked 단추에 닿는다').toBe(blocked);

      const before = looks(blocked)['background-color'];
      if (await realHover(blocked)) {
        await animationsDone();
        await expect(blocked.matches(':hover'), '마우스가 단추 위').toBe(true);
        await expect(looks(blocked)['background-color'], `${variant}: 마우스를 올린 뒤의 바탕`).toBe(before);
      }
    },
  };
}

export const PrimaryBlocked = blockedStory('primary');
export const SecondaryBlocked = blockedStory('secondary');
export const QuietBlocked = blockedStory('quiet');
export const DangerBlocked = blockedStory('danger');
export const InternalBlocked = blockedStory('internal');

/** `pending` stays the spinner with the cursor `progress`. */
export const PendingStays: Story = {
  args: { pending: true, pendingLabel: '보내는 중…' },
  play: async ({ canvasElement }) => {
    const button = canvasElement.querySelector<HTMLButtonElement>('button[data-pending]');
    await expect(button, 'pending 단추').not.toBeNull();
    if (!button) return;
    await animationsDone();
    await expect(button.getAttribute('aria-disabled'), 'aria-disabled').toBe('true');
    await expect(getComputedStyle(button).cursor, '커서').toBe('progress');
  },
};
