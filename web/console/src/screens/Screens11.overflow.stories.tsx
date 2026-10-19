// 11 묶음이 바꾼 콘솔 화면을 360·820·1280 폭, 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와 바뀐 부분이 가로로
// 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md "360px에서 단추와 선이 넘치지 않는다").
// 로그인 전 C-1: OIDC가 켜져 단추 "회사 계정으로 로그인"과 "또는"이 있고, `?oidc=unavailable`의 가장 긴 오류 띠가 있다.
// C-18의 "OIDC 로그인" 구역: 켜짐(콘솔과 포털), 연말의 긴 시각 둘, int32 끝의 실패 수, 가장 긴 실패 종류 문장.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch11.ts)의 답으로 그린다.
//
// P-1은 여기서 그리지 않는다: story는 콘솔 폴더에만 있고 콘솔은 포털을 가져오지 못한다(ADR D-57). 같은 조각을 쓴다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { OIDC_OFF, setOidcScenario, type OidcScenario } from '../__tests__/storyFetch11';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, buttonNamed, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
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

const meta = { title: 'Overflow/11 콘솔 화면' };
export default meta;

type Found = { el: Element; what: string }[];

const OIDC_BUTTON = '회사 계정으로 로그인';
const UNAVAILABLE = '회사 로그인 서비스에 연결하지 못했습니다. 잠시 뒤 다시 시도하거나 비밀번호로 로그인하세요.';
const OIDC_TITLE = 'OIDC 로그인';

const LOGIN: OidcScenario = { signInOidc: true, signedOut: true, status: OIDC_OFF };
const STATUS: OidcScenario = {
  signInOidc: false,
  signedOut: false,
  status: {
    enabled: true,
    apps: ['CONSOLE', 'PORTAL'],
    lastSuccessAt: '2026-12-31T14:59:59Z',
    providerFailures: 2_147_483_647,
    lastFailure: { kind: 'DISCOVERY', at: '2026-12-31T14:58:47Z' },
  },
};

function screenStory(width: Width, theme: Theme, given: OidcScenario, path: string, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)], labels: { ACME: [] } }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setOidcScenario(given);
      window.history.replaceState(null, '', path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-1 before sign-in with the OIDC button and the band of `?oidc=unavailable`. */
async function loginParts(canvas: HTMLElement): Promise<Found> {
  await waitFor(() => Array.from(document.querySelectorAll('input[type="password"]'))[0], 'C-1의 비밀번호 칸', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-1의 main');
  const button = await waitFor(() => buttonNamed(main, OIDC_BUTTON), `C-1의 "${OIDC_BUTTON}"`, 8000);
  await waitFor(() => (textOf(main).includes('또는') ? true : null), 'C-1의 "또는"');
  const band = await waitFor(
    () => Array.from(main.querySelectorAll<HTMLElement>('[role="alert"]')).find((el) => textOf(el).includes(UNAVAILABLE)) ?? null,
    'C-1의 오류 띠(unavailable)',
  );
  return [
    { el: main, what: 'C-1의 main' },
    { el: band, what: 'C-1의 오류 띠' },
    { el: button, what: `C-1의 "${OIDC_BUTTON}"` },
  ];
}

/** C-18 drawn with the OIDC part on, its two times, the largest count and a failure. */
async function statusParts(canvas: HTMLElement): Promise<Found> {
  const main = await waitFor(() => mainOf(canvas), 'C-18의 main', 8000);
  const heading = await waitFor(
    () => Array.from(main.querySelectorAll('h2')).find((h) => textOf(h) === OIDC_TITLE) ?? null,
    `C-18의 구역 "${OIDC_TITLE}"(h2)`,
    8000,
  );
  const part = heading.closest('section') ?? heading.parentElement ?? heading;
  await waitFor(() => (textOf(part).includes('켜짐: 콘솔과 포털') ? true : null), '구역의 "켜짐: 콘솔과 포털"');
  await waitFor(() => (textOf(part).includes('마지막 실패: 제공자 정보를 받지 못함') ? true : null), '구역의 마지막 실패');
  await waitFor(() => (part.querySelectorAll('time').length >= 2 ? true : null), '구역의 시각 둘');
  return [
    { el: main, what: 'C-18의 main' },
    { el: part, what: `C-18의 구역 "${OIDC_TITLE}"` },
  ];
}

export const Login360Light = screenStory(360, 'light', LOGIN, '/login?oidc=unavailable', loginParts);
export const Login360Dark = screenStory(360, 'dark', LOGIN, '/login?oidc=unavailable', loginParts);
export const Login820Light = screenStory(820, 'light', LOGIN, '/login?oidc=unavailable', loginParts);
export const Login820Dark = screenStory(820, 'dark', LOGIN, '/login?oidc=unavailable', loginParts);
export const Login1280Light = screenStory(1280, 'light', LOGIN, '/login?oidc=unavailable', loginParts);
export const Login1280Dark = screenStory(1280, 'dark', LOGIN, '/login?oidc=unavailable', loginParts);

export const C18Oidc360Light = screenStory(360, 'light', STATUS, '/system', statusParts);
export const C18Oidc360Dark = screenStory(360, 'dark', STATUS, '/system', statusParts);
export const C18Oidc820Light = screenStory(820, 'light', STATUS, '/system', statusParts);
export const C18Oidc820Dark = screenStory(820, 'dark', STATUS, '/system', statusParts);
export const C18Oidc1280Light = screenStory(1280, 'light', STATUS, '/system', statusParts);
export const C18Oidc1280Dark = screenStory(1280, 'dark', STATUS, '/system', statusParts);
