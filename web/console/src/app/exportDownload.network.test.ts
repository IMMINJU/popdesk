// 10a 묶음 design.md 0절 11의 내보내기 확인 도움 startExport에서 HEAD가 연결 끊김으로 끝나는 경우(10a verify.md 4절: 그 줄의
// 문구를 빈 글로 바꿔도 모든 테스트가 지났다). 옮겨 가지 않고, 상태 0과 screens.md 2절의 연결 끊김 문구(서버 오류와 같은 말)를
// 준다. 같은 주소를 다시 물으면 앞 실패에 묶이지 않고 새로 묻는다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { on } from '../__tests__/console';
import { startExport } from './exportDownload';

const ACCOUNT_EXPORT = '/api/console/accounts/2/export';
const LOST = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';

describe('내보내기 확인 도움 startExport: 연결 끊김', () => {
  test('S10A-AC-29 startExport 연결 끊김: 옮겨 가지 않고 상태 0과 연결 끊김 문구를 준다', async () => {
    on('head', ACCOUNT_EXPORT, () => HttpResponse.error());
    const went: string[] = [];
    const asked: (number | null)[] = [];

    const result = await startExport(ACCOUNT_EXPORT, {
      navigate: (to) => {
        went.push(to);
      },
      busyText: (seconds) => {
        asked.push(seconds);
        return `몰림 ${String(seconds)}`;
      },
    });

    expect(result).toEqual({ ok: false, status: 0, text: LOST });
    expect(went).toEqual([]);
    expect(asked).toEqual([]);
  });

  test('S10A-AC-29 startExport 연결 끊김 뒤 다시 누르면 새로 묻고, 이번에 되면 옮겨 간다', async () => {
    on('head', ACCOUNT_EXPORT, () => HttpResponse.error());
    const went: string[] = [];
    const options = {
      navigate: (to: string) => {
        went.push(to);
      },
      busyText: () => '몰림',
    };
    const first = await startExport(ACCOUNT_EXPORT, options);

    on('head', ACCOUNT_EXPORT, () => new HttpResponse(null, { status: 200 }));
    const second = await startExport(ACCOUNT_EXPORT, options);

    expect(first).toMatchObject({ ok: false, status: 0 });
    expect(second).toEqual({ ok: true });
    expect(went).toEqual([ACCOUNT_EXPORT]);
  });
});
