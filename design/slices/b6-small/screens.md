# b6 묶음 화면: 작은 화면 고침 둘째

> 수용 기준은 `acceptance.md`다. 새 토큰과 새 컴포넌트는 없다. 한국어 글은 바뀌지 않는다.

## 1. C-9 소속 고객 표 (B6-AC-01, 02)

- 이메일 칸은 낱말 가운데에서 끊을 수 있다(`overflow-wrap: anywhere`). 이 칸이 표 너비의 남는 몫을 받는다.
- 이름 칸(`.pd-person`)은 줄바꿈하지 않는다. 긴 이름(견본의 `LONG_PERSON`)이 칸을 넘으면 줄임표로 자르고 `title`로 전체를 보인다. 이름 칸에 최대 너비를 준다(구현 탭이 정하고 보고에 적는다).
- 상태·담당자 칸의 배지는 줄 하나다.
- 동작 칸(`.pd-row-actions`)의 단추는 글이 줄 하나다(`white-space: nowrap`). 단추가 둘이고 칸이 좁으면 위아래로 쌓고 오른쪽으로 맞춘다.
- 담당 상담원 표, 가입 요청 표도 같은 꼴이면 같은 규칙을 받는다. 이 둘은 기준에 넣지 않는다(구현 탭이 820px에서 한 번 본다).
- 360px는 바뀌지 않는다(표 틀 안에서 민다). 구현 탭이 브라우저로 한 번 본다.

## 2. C-11 휴지통 표 (B6-AC-03)

- 동작 칸은 1절과 같다(단추 글 줄 하나, 좁으면 쌓음).
- 문의 칸(`.pd-trash-issue`, 지금 `min-width: 14rem`)과 고객사 칸이 줄을 바꿔 너비를 내준다. 제목은 낱말 사이에서 줄을 바꾼다.
- 시각 칸("지운 때", "영구 삭제")은 줄 하나다.

## 3. P-3 필터 줄 (B6-AC-04)

- PC 폭의 필터 줄(`.pd-filters-more`가 `display: contents`인 줄)을 `align-items: flex-end`로 맞춘다. `.pd-filters-more > .pd-check`의 `margin-top`을 지운다. "필터 지우기" 링크(`.pd-filters-clear`)도 같다.
- 칸 이름이 있는 입력 칸과 칸 이름이 없는 체크·링크가 한 줄에 서면 아래쪽이 맞는다. 체크가 줄의 첫 칸이 되면 위쪽에 붙는다.
- 360px의 접힌 필터(세로로 쌓음)는 그대로다.

## 4. 자동 정리 알림 영어 (B6-AC-05)

| 키 | 영어 |
|---|---|
| `inbox.retentionArmed` | `Automatic issue cleanup is on. From tomorrow, {count} {count|issue|issues} closed more than {days} {days|day|days} ago {count|moves|move} to the trash.` |
| `inbox.retentionArmedNone` | `Automatic issue cleanup is on. No issue has been closed for over {days} {days|day|days} yet.` |

- 한국어 두 문장은 그대로다. 한국어 `retentionArmed`는 끝에 마침표가 없지만 이번에 고치지 않는다(바꾸면 한국어 화면이 바뀐다).
- C-7은 지금처럼 `tf`에 화면 언어 꼴로 쓴 수를 넘긴다. `tf`가 "1"과 "1,234"를 수로 읽는 것은 b5에서 본 그대로다.

## 5. 첫 로드의 탭 제목 (B6-AC-06)

- `web/console/index.html`의 `<title>`을 "popdesk"로 바꾼다. 포털은 이미 "popdesk"다.
- 빌드한 `dist/index.html`도 같은 제목이 된다. nginx 설정과 서버는 바뀌지 않는다.
