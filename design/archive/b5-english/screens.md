# b5 묶음 화면: 영어 글의 꼴

> 수용 기준은 `acceptance.md`다. 새 화면, 새 토큰, 새 컴포넌트는 없다.

## 1. 언어가 정해지는 차례

1. 로그인해 있으면 `Me.locale`.
2. 로그인 전이면 `navigator.languages[0]`(없으면 `navigator.language`)이 `ko`로 시작하면 `ko`, 그 밖은 `en`.
3. C-6·P-6에서 언어를 저장하면 답을 받은 뒤 바로 바꾼다. 저장이 실패하면 바꾸지 않는다.
4. 로그아웃하면 2로 돌아간다.

## 2. 영어 문장의 꼴

- 짧고 쉬운 말. 문장 첫 글자만 대문자(sentence case): "Save changes", "Issue list". 제목도 같다.
- 단추는 동사로 시작한다: "Save", "Cancel", "Assign to me", "Remove from company".
- 오류는 무엇이 안 됐는지와 할 일: "Could not connect to the server. Try again in a moment."
- 수를 넣는 문장은 단수·복수를 나눈다: "1 issue", "3 issues". 0은 복수 꼴("0 issues") 또는 따로 둔 문장(b2의 자동 정리 0건처럼 이미 있는 것).
- 한국어 조사 고르기(`particles.ts`)는 한국어 문장에서만 쓴다. 영어 문장은 이름을 그대로 넣는다.
- 고객에게 보이는 상태 말은 `ui/words.ts`의 고객용과 상담용을 나눈 그대로 둘 다 영어로 둔다.
- 날짜는 `Intl.DateTimeFormat('en', …)`의 기본 꼴을 쓴다. 상대 시각은 `RelativeTime`이 `<html lang>`으로 고른다.

## 3. 용어표 (같은 말은 같은 영어로)

| 한국어 | 영어 |
|---|---|
| 문의 | issue |
| 고객사 | company |
| 고객 | customer |
| 상담원 | agent |
| 관리자 | admin |
| 담당자 | assignee |
| 고객사 담당자(매니저) | company manager |
| 댓글 / 공개 답변 / 내부 메모 | comment / public reply / internal note |
| 알림함 | inbox |
| 라벨 | label |
| 마감일 | due date |
| 작업 | task |
| 휴지통 | trash |
| 감사 로그 | audit log |
| 접수 / 확인 / 처리 중 / 해결됨 / 종료 | (상담용) received / confirmed / in progress / resolved / closed. 고객용 말은 `ui/words.ts`의 고객용 한국어 뜻을 따라 짓는다 |
| 우선순위 낮음·보통·높음·긴급 | low, normal, high, urgent |
| 익명화됨 | anonymized |
| 정지 | suspended |
| 보관됨 | archived |
| 초대 | invitation |
| 시스템 상태 | system status |
| 브랜딩 | branding |
| 다시 불러오기 | Reload |

이미 `@popdesk/i18n/en.ts`에 있는 223개 키의 영어는 이 표와 어긋나면 표에 맞춘다(구현 탭이 고치고 보고에 적는다).
