# b2 묶음 화면: 작은 동작 고침

> 수용 기준은 `acceptance.md`다. 새 토큰과 새 컴포넌트는 없다.

## 1. C-9 소속 고객 표 (B2-AC-04)

| 고객 | 상태 칸 | 줄의 단추 |
|---|---|---|
| 익명(`anonymized` 참), 담당자 아님 | `Badge tone="neutral"` "익명화됨"(C-10 `C10MemberList.tsx`와 같은 글과 꼴) | "담당자로 지정" 없음. 그 밖의 단추(소속 빼기)는 지금 그대로 |
| 익명, 담당자 | 같음 | "담당자 해제"(지금 그대로) |
| 정지(익명 아님) | "정지" 그대로 | 지금 그대로("담당자로 지정" 있음) |

- 담당자 지정이 400 `ACCOUNT_ANONYMIZED`로 거절되면(다른 관리자가 그사이 익명화한 경우) 띠 문장은 C-10과 같은 "이미 익명화한 계정입니다."다(`C10MemberList.tsx`의 것, 새 글 없음). 표를 다시 읽는다.

## 2. C-7 자동 정리 켜짐 알림 (B2-AC-06)

- `retentionCount`가 0이면 `inbox.retentionArmedNone`: 한국어 "문의 자동 정리가 켜졌습니다. 지금은 종료된 지 {days}일이 지난 문의가 없습니다.", 영어 "Automatic issue cleanup is on. No issue has been closed for over {days} days yet."
- 1 이상이면 `inbox.retentionArmed` 그대로.
