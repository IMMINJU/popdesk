// 12 묶음이 바꾼 콘솔 화면을 820·1280 폭, 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와 바뀐 부분이 가로로
// 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md "820px에서 줄이 넘치지 않는다").
// C-18의 "메일 받기" 구역: 켜짐, 연말의 긴 시각 둘, int32 끝의 실패한 회차 수와 메일 수, 가장 긴 실패 종류 문장, 격리 설명.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch11.ts, storyFetch12.ts)의 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { OIDC_OFF, setOidcScenario } from '../__tests__/storyFetch11';
import { setMailInScenario } from '../__tests__/storyFetch12';
import type { ReactElement } from 'react';
import type { Schemas } from '../api';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
import { App } from '../App';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/12 콘솔 화면' };
export default meta;

type Found = { el: Element; what: string }[];

const MAIL_IN_TITLE = '메일 받기';
const INT32_MAX = 2_147_483_647;

const MAIL_IN: Schemas['MailInStatus'] = {
  enabled: true,
  offReason: null,
  lastSuccessAt: '2026-12-31T14:59:59Z',
  roundFailures: INT32_MAX,
  lastFailure: { kind: 'UNSUPPORTED', at: '2026-12-31T14:58:47Z' },
  retrying: INT32_MAX,
  quarantined90d: INT32_MAX,
};

function screenStory(width: Width, theme: Theme): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)], labels: { ACME: [] } }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setOidcScenario({ signInOidc: false, signedOut: false, status: OIDC_OFF });
      setMailInScenario(MAIL_IN);
      window.history.replaceState(null, '', '/system');
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await statusParts(canvasElement));
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-18 drawn with the receiving email part on, its two times, the largest counts, a failure and the quarantine note. */
async function statusParts(canvas: HTMLElement): Promise<Found> {
  const main = await waitFor(() => mainOf(canvas), 'C-18의 main', 8000);
  const heading = await waitFor(
    () => Array.from(main.querySelectorAll('h2')).find((h) => textOf(h) === MAIL_IN_TITLE) ?? null,
    `C-18의 구역 "${MAIL_IN_TITLE}"(h2)`,
    8000,
  );
  const part = heading.closest('section') ?? heading.parentElement ?? heading;
  await waitFor(() => (textOf(part).includes('마지막 실패: 메일 서버가 메일 옮기기를 지원하지 않음') ? true : null), '구역의 마지막 실패');
  await waitFor(() => (textOf(part).includes(`격리한 메일(90일): ${INT32_MAX.toLocaleString('ko')}통`) ? true : null), '구역의 격리한 메일');
  await waitFor(() => (textOf(part).includes(`다시 시도할 메일: ${INT32_MAX.toLocaleString('ko')}통`) ? true : null), '구역의 다시 시도할 메일');
  await waitFor(() => (part.querySelectorAll('time').length >= 2 ? true : null), '구역의 시각 둘');
  return [
    { el: main, what: 'C-18의 main' },
    { el: part, what: `C-18의 구역 "${MAIL_IN_TITLE}"` },
  ];
}

export const C18MailIn820Light = screenStory(820, 'light');
export const C18MailIn820Dark = screenStory(820, 'dark');
export const C18MailIn1280Light = screenStory(1280, 'light');
export const C18MailIn1280Dark = screenStory(1280, 'dark');
