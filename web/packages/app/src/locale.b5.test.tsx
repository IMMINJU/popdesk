// b5 묶음 언어 장치(acceptance.md 5절 둘째 줄). 두 앱이 함께 쓰는 SessionProvider와 t·tf로 본다:
// 로그인해 있으면 Me.locale(B5-AC-01), 로그인 전이면 브라우저 언어(B5-AC-02), 바뀌면 새로고침 없이
// 바뀜(B5-AC-03), <html lang>과 document.title(B5-AC-04), 수와 날짜(B5-AC-07).
// 본문을 읽지 않고 기준과 시그니처로 썼다. 가정: 언어는 SessionProvider가 든 `me.locale`에서 오고,
// `t`·`tf`는 부를 때의 화면 언어를 읽는다.
import { RelativeTime } from '@popdesk/ui';
import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useDocumentTitle } from './hooks';
import { t, tf } from './locale';
import { SessionProvider } from './session';
import { useSession } from './sessionContext';

const HANGUL = /[가-힣]/;
const AT = '2026-10-08T03:15:00Z';

function languages(list: readonly string[], language = list[0] ?? '') {
  Object.defineProperty(window.navigator, 'languages', { value: list, configurable: true });
  Object.defineProperty(window.navigator, 'language', { value: language, configurable: true });
}

/** A screen of the shared package: a title, a text and a time, and a way to change who is signed in. */
function Probe() {
  const { setMe } = useSession<{ locale: 'ko' | 'en' }>();
  const title = t('myInfo.title');
  useDocumentTitle(title, 'popdesk');
  return (
    <main>
      <h1>{title}</h1>
      <p data-testid="said">{t('passwordForgot.title')}</p>
      <p data-testid="time">
        <RelativeTime dateTime={AT} now={new Date('2026-10-08T06:15:00Z')} focusable={false} />
      </p>
      <button type="button" onClick={() => {
          setMe({ locale: 'en' });
        }}>
        en
      </button>
      <button type="button" onClick={() => {
          setMe({ locale: 'ko' });
        }}>
        ko
      </button>
      <button type="button" onClick={() => {
          setMe(null);
        }}>
        out
      </button>
    </main>
  );
}

function draw() {
  render(
    <SessionProvider>
      <Probe />
    </SessionProvider>,
  );
}

const said = () => screen.getByTestId('said').textContent;
const press = (name: string) => {
  act(() => {
    screen.getByRole('button', { name }).click();
  });
};

beforeEach(() => {
  document.documentElement.lang = '';
  document.title = '';
  languages(['ko']);
});

describe('화면 언어 (b5 묶음)', () => {
  it.each([
    [['ko-KR'], 'ko', '비밀번호 찾기'],
    [['ko'], 'ko', '비밀번호 찾기'],
    [['en-US'], 'en', 'Forgot password'],
    [['ja'], 'en', 'Forgot password'],
    [['en-US', 'ko-KR'], 'en', 'Forgot password'],
  ])('B5-AC-02 로그인 전: 브라우저 언어 %j → %s', async (list, lang, text) => {
    languages(list);
    draw();

    await waitFor(() => {
      expect(document.documentElement.lang).toBe(lang);
    });
    expect(said()).toBe(text);
  });

  it('B5-AC-02 navigator.languages가 비면 navigator.language를 본다', async () => {
    languages([], 'en-GB');
    draw();

    await waitFor(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expect(said()).toBe('Forgot password');
  });

  it('B5-AC-01 B5-AC-04 로그인하면 브라우저 언어가 아니라 Me.locale: 브라우저 ko, 계정 en → 글, html lang, document.title이 영어', async () => {
    languages(['ko-KR']);
    draw();
    press('en');

    await waitFor(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expect(said()).toBe('Forgot password');
    expect(screen.getByRole('heading', { level: 1 }).textContent).not.toMatch(HANGUL);
    await waitFor(() => {
      expect(document.title).toMatch(/ - popdesk$/);
      expect(document.title).not.toMatch(HANGUL);
    });
  });

  it('B5-AC-01 계정 ko는 브라우저가 en-US여도 한국어다', async () => {
    languages(['en-US']);
    draw();
    press('ko');

    await waitFor(() => {
      expect(document.documentElement.lang).toBe('ko');
    });
    expect(said()).toBe('비밀번호 찾기');
    await waitFor(() => {
      expect(document.title).toBe('내 정보 - popdesk');
    });
  });

  it('B5-AC-03 B5-AC-04 언어가 바뀌면 다시 그리지 않아도(같은 화면) 글, html lang, document.title이 함께 바뀌고, 로그아웃하면 브라우저 언어로 돌아간다', async () => {
    languages(['ko-KR']);
    draw();
    press('ko');
    await waitFor(() => {
      expect(document.title).toBe('내 정보 - popdesk');
    });

    press('en');
    await waitFor(() => {
      expect(said()).toBe('Forgot password');
      expect(document.documentElement.lang).toBe('en');
      expect(document.title).not.toMatch(HANGUL);
    });

    press('ko');
    await waitFor(() => {
      expect(said()).toBe('비밀번호 찾기');
      expect(document.documentElement.lang).toBe('ko');
      expect(document.title).toBe('내 정보 - popdesk');
    });

    languages(['en-US']);
    press('out');
    await waitFor(() => {
      expect(said()).toBe('Forgot password');
      expect(document.documentElement.lang).toBe('en');
    });
  });

  it('B5-AC-08 B5-AC-01 tf는 화면 언어의 문장에 값을 넣는다(영어는 한국어 세는 말을 쓰지 않는다)', async () => {
    languages(['en-US']);
    draw();
    press('en');

    await waitFor(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expect(tf('inbox.count', { count: 3 })).toContain('3');
    expect(tf('inbox.count', { count: 3 })).not.toMatch(HANGUL);
    expect(tf('inbox.who', { name: 'Morgan' })).toContain('Morgan');
    expect(tf('inbox.who', { name: 'Morgan' })).not.toMatch(HANGUL);
  });

  it.each([
    ['ko', /^3시간 전$/, /2026년 10월 8일/],
    ['en', /^3 hours ago$/, /October 8, 2026/],
  ] as const)('B5-AC-07 계정 %s: RelativeTime의 상대 시각과 절대 시각(title)은 화면 언어다', async (locale, relative, absolute) => {
    languages(locale === 'ko' ? ['en-US'] : ['ko-KR']);
    draw();
    press(locale);

    await waitFor(() => {
      expect(document.documentElement.lang).toBe(locale);
    });
    await waitFor(() => {
      const time = screen.getByTestId('time').querySelector('time');
      expect(time?.textContent ?? '').toMatch(relative);
      expect(time?.getAttribute('title') ?? '').toMatch(absolute);
    });
  });
});
