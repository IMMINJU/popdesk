# 4번 묶음 작업 (C-8)

> 한 세션 크기로 나눈 구현 작업이다. 작업 하나는 "이 테스트들을 통과시킨다"다. 묶음이 끝나면 지운다(D-27).
> 탭 지시서는 `C:\Users\minju\.claude\popdesk\tasks\s4-i-*.md`에 있다. 서버 탭은 한 번에 하나, 웹 탭은 서버가 합쳐진 뒤에 띄운다(`process.md` "검증에서 되돌아가는 일을 줄이려고 바꾼 것").

## 1. 탭과 차례

| 차례 | 탭 | 맡은 것 | 통과시킬 테스트 |
|---|---|---|---|
| 1 | `s4-i-comments` (서버) | V12 전부(뒤 탭이 쓸 칸과 표까지), 댓글 고치기와 지우기, 지운 흔적, 댓글의 첨부 지우기, 멘션 저장과 검증, 멘션 후보, 댓글 달기·고치기의 요청 수 | `issue`의 `CommentEditTest`, `CommentEditRateLimitTest`, `CommentNotFoundTest`, `CommentDeleteTest`, `MentionTest`, `MentionCandidatesTest`, `ops.TalkCheckOrderTest`, `ops.TalkLogTest`에서 알림과 메일에 기대지 않는 것 |
| 2 | `s4-i-notify` (서버) | 문의 알림 클래스(받는 사람을 정하는 한 곳), 알림함 넣기, 포털·콘솔 알림 API, 볼 수 있는 문의로 거르기, 새 메일 여섯과 보내기 직전의 확인, 소속 요청 메일, 댓글을 지울 때 항목 지우기, 견본 생성기, 문서(`permissions.md`, `conventions.md`, `configuration.md`, `upgrade.md`) | `inbox`의 다섯, `notify`의 `TalkMailTest`·`KoreanTalkMailTest`·`NoSmtpTalkMailLogTest`·`TalkWebhookTest`·`CommentDeleteWebhookTest`, `boundary`의 `TalkBoundaryTest`·`TalkStaffIdentityTest`, `manager.MembershipRequestMailTest`, `project.NoStoreTest`, `deploy`의 `TalkUpgradeTest`·`TalkSampleTest`, 앞 탭이 남긴 것 |
| 3 | `s4-i-web-console` (웹) | `ui`의 `PeoplePicker`, `ConfirmDialog`의 `error`, 공용 줄바꿈 규칙, 콘솔 머리의 알림 링크와 안 읽은 수, C-7, C-4의 댓글 고치기·지우기와 "알릴 사람", `#comment-ID` | `ui`의 `PeoplePicker.test`, `ConfirmDialog.error.test`, 콘솔의 `layout.inbox.test`, `C7Inbox.test`, `C4IssueDetail.comments4.test`, `C4IssueDetail.mentions.test` |
| 4 | `s4-i-web-portal` (웹) | 포털 머리의 알림 링크와 안 읽은 수, P-7, P-5의 댓글 고치기·지우기, `#comment-ID` | 포털의 `layout.inbox.test`, `P7Inbox.test`, `P5IssueDetail.comments4.test` |

- 웹 탭 둘은 같은 공용 컴포넌트(`CommentItem`, `AppShell`)를 쓴다. 공용 컴포넌트와 공용 CSS를 고치는 것은 콘솔 탭(3)이고, 포털 탭(4)은 그 뒤에 뜬다.
- 웹 탭은 끝내기 전에 합친 코드의 이미지를 띄워 브라우저로 자기 화면을 본다.

## 2. 구현 탭이 코드로 지킬 것 (테스트로는 볼 수 없는 것)

| 것 | 까닭 | 맡는 탭 |
|---|---|---|
| 포털 알림 목록의 SQL에 "고객에게 가는 종류만" 조건을 둔다 | HTTP로는 고객 계정에 관리자 종류의 항목을 넣을 길이 없어 테스트가 없다(`design.md` 4절, 서버 점검 6-2) | `s4-i-notify` |
| 포털의 목록과 안 읽은 수도 볼 수 있는 문의로 거른다 | 지금은 고객이 볼 수 없게 된 자기 문의가 없어 테스트가 없다(서버 점검 6-3). 7번의 휴지통에서 쓰인다 | `s4-i-notify` |
| 알림함 첫 쪽의 쿼리가 `(account_id, id)` 색인을 탄다 | 계약 탭의 실험 3에서 기본 키를 거꾸로 훑었다. 계획을 보고에 붙인다 | `s4-i-notify` |
| 받는 사람의 판정(자기 제외, `ACTIVE`, 볼 수 있음, 멘션과 겹침)은 한 클래스에만 있다 | `design.md` 0절 2. 서비스마다 따로 넣으면 어긋난다 | `s4-i-notify` |
| 지운 댓글의 내용은 UPDATE로 빈 글이 된다. 옛 글을 다른 칸이나 표에 남기지 않는다 | S4-AC-18 | `s4-i-comments` |
| 닫힌 문의의 지운 댓글을 고치면 `COMMENT_DELETED`가 먼저다 | `design.md` 2절 ⑤ | `s4-i-comments` |
| 지운 댓글의 웹훅 대기 행은 건드리지 않는다(그대로 나간다) | 웹훅 글에 댓글 내용이 없다 | `s4-i-comments` |

## 3. 읽을 보고

| 탭 | 읽을 절 |
|---|---|
| 서버 둘 | `s4-c4-server-report.md` 1·2·7·9절, `s4-t-comments-report.md` 0절(도움)과 5절(가정), `s4-t-inbox-report.md` 4절(메일 여섯의 글)과 6절(가정), `s4-t-gates-report.md`의 가정 절, `s4-c7-server-report.md` 1절의 "통과하는 틀린 구현"(하지 말아야 할 것), `s4-tfix-server-report.md` |
| 웹 둘 | `s4-c4-web-report.md`, `s4-t-web-console-report.md`와 `s4-t-web-portal-report.md`의 가정 절, `s4-c7-web-report.md`의 "틀린 구현의 결과"(하지 말아야 할 것)와 "0절 표에 없는 제약 넷", `s4-tfix-web-report.md` |
