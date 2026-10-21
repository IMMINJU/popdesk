# 12 묶음(메일 받기) 검증 (C-10)

> 2026-10-19 메인이 이 PC에서 했다. GitHub Actions 10월분을 다 써서 CI는 돌지 않았다.
> C-10은 닿은 부분만 돈다(`process.md` C-10 첫 줄).
> 숫자는 각 실행의 로그(`~\.claude\popdesk\local-ci\`, `tasks\s12-*`)와 탭 보고에서 옮겼다.

## 1. 구현 탭 넷이 끝났고 맡은 테스트는 모두 통과한다

| 탭 | 맡은 것 | 결과 |
|---|---|---|
| `s12-i-base` | V25, 설정과 시작 검사, 보내는 메일 머리글과 `mail_sent`, 영구 삭제 정리, 시스템 상태 `mailIn`, 설정 문서와 compose | 맡은 클래스 7개 통과. 같이 돈 notify·branding·notification 382, start 156(흔들린 `StorageStartCheckTest` 1건은 따로 23/23), ops·trash 234, boundary 81에서 실패 0 |
| `s12-i-mailin` | 순수 계산 여섯, 회차와 메일함, 메일 하나의 처리, 안내 메일, 기록·익명화·정리 작업·경보, 개인정보 문서 | `pending.txt` 서버 클래스 전부 통과(mailin 22클래스 120, `MailInStartTest` 2, `CleanupStalledTest` 5), 이미지로 `MailInSampleTest` 1과 `UpgradeHarnessTest` 7. 같이 돈 notify·issue 346, ops·trash 234, attachment·privacy·boundary 430, start 158, 구조 검사 96에서 실패 0(`NoWebhookTest` 1건은 묶음에서 한 번 흔들리고 혼자 통과) |
| `s12-i-web` | C-18 메일 받기 구역, C-7 경보 두 종류, story | console 1,509를 포함한 패키지 테스트 전체, story 실행기 741, typecheck, lint, build, 포털 번들 검사 통과. dup 5.53% → 5.51%. 공용 컴포넌트 API는 그대로 |
| `s12-i-fix` | C-10 코드 점검 뒤 고침(`design.md` 0절 16 ①~④)과 단위 테스트 실패 둘 | 단위 131, mailin 22클래스와 `MailInStartTest` 122, 구조 검사 통과 |

- 잠긴 테스트가 틀린 것은 둘이었다. `notify.SandboxMailTest`는 메일 종류를 13개로 묶어 두어 안내 종류 셋(0절 11)과 부딪혔다. 샌드박스는 메일 받기를 끄므로 그 셋을 따로 두게 메인이 고쳤다(`52ada88`, 잠금 `02b9858`). 웹 story `Screens12.overflow`는 메인이 견본만 `UNSUPPORTED`로 바꾸고 기다리는 문장을 두었던 것이라 메인이 고쳤다(`61b7946`).
- 합친 뒤 main의 서버 코드는 각 탭이 마지막으로 돌린 트리와 같다. 닿은 서버 패키지는 탭 결과로 갈음했다.

## 2. 이 PC에서 돈 것 (닿은 부분만, main `be0646f`)

| 무엇 | 결과 | 시간 |
|---|---|---|
| 관문: `tests-lock`, `check-test-style`, `check-tenant-id`, `check-locked-paths`, gitleaks, 구조 검사 5 | 통과 | 2분 3초 |
| `server-openapi`(계약 파일 다시 만들기) | 통과, 차이 없음 | 34초 |
| `popdesk:test` 이미지 빌드 | 통과 | 55초 |
| deploy `MailInSampleTest`, `UpgradeHarnessTest` | 1, 7 통과 | 9분 15초 |
| 숨긴 E2E `MailInFlowTest` | 8 통과(세 번째 실행) | 9분 11초 |
| 순수 계산 단위 테스트 8클래스 | 131 통과(고친 뒤, 고침 탭이 돌림) | 46초 |

숨긴 E2E는 두 번 실패한 뒤 지났다. 한 번은 숨긴 테스트의 기대가 메일 표준(RFC 2045)과 어긋난 것이라 메인이 숨긴 쪽에서 고쳤고, 한 번은 그 고침이 파일에 들어가지 않은 채 돈 것이다. 운영 코드는 고치지 않았다.

## 3. 변이 테스트

| 대상 | 만든 변이 | 잡음 | 살아남음 |
|---|---|---|---|
| 서버 PIT 일곱 클래스(고치기 전) | 233 | 221 | 11, 커버 안 됨 1 |
| 서버 PIT `BodyFit`, `MailInSettings`(고친 뒤) | 79 | 75 | 3, 커버 안 됨 1 |

- 일곱 클래스: `AutomatedMail`, `IssueKeyInSubject`, `InboundAddress`, `HtmlText`, `QuoteTrim`, `BodyFit`, `MailInSettings`. `MailMessage.title`·`fileName`은 단위 테스트만 있다.
- 단위 테스트는 테스트 쪽 서브에이전트가 기준 문서와 Javadoc을 기준으로 썼다(`dcff1bc`, 잠금 `84cacc7`). 코드가 문서와 다른 곳 둘(덧붙일 줄만으로 넘칠 때 본문을 다시 채움, 앞자리 0이 많은 `IMAP_PORT`를 거절)을 찾았고 고침 탭이 코드를 고쳤다.
- 살아남은 것: 결과가 같은 경계 변이(`AutomatedMail` 126, `InboundAddress` 44·72의 다섯, `BodyFit` `fit`·`cut`), `MailInSettings.toString`(기준이 내용을 정하지 않음, 비밀값이 없다는 것은 테스트가 봄), `AutomatedMail` 158(주석 `(x)` 안의 글을 구분자로 볼지, 기준이 정하지 않음). 커버 안 된 `BodyFit.cut`의 한 줄은 고친 `fit`에서 닿을 수 없다.
- 웹에는 새 순수 함수가 없어 Stryker는 돌지 않았다.

## 4. 메인이 코드로 본 것

테스트가 약해 C-10에서 코드로 보기로 한 곳이다. Claude 서브에이전트가 읽고 메인이 고칠 것을 골랐다.

- 결과를 훅에서 쓰기: 새 문의는 `issueService.file`의 Runnable 안에서 `storeFiled` 다음 `records.decide`, 댓글은 `CommentService.add`의 `LongConsumer` 안에서 쓴다. `decide`는 결과가 비었을 때만 쓰고 아니면 던져 되돌린다. `mail_received.issue_id`가 `DEFERRABLE INITIALLY DEFERRED`라 문의 행보다 먼저 써도 된다. 두 번 만들어지는 길도, 기록만 남는 길도 찾지 못했다.
- 시도 수 커밋: `transactions.run(records.attempt)`가 따로 커밋하고, 5번 뒤 다음 회차에서 `FAILED`로 격리한다. `DMARC_FAIL` 기록은 진짜 메일이 오면 시도 수를 1로 되돌린다.
- 댓글 첨부: 잠금 차례(문의 → 댓글 → 첨부 → 이력)가 포털 업로드와 같고, 50MB 합계를 잠금 아래에서 다시 본다. 되돌리면 대기 행은 정리 작업이 치운다.
- `COPY`·`UID EXPUNGE` 길: 일반 `EXPUNGE`가 나가는 길은 없다. 여기서 결함 하나를 찾았다. `MOVE`는 있고 `UIDPLUS`가 없는 서버에서 Angus의 `moveUIDMessages`가 던져 받는 폴더가 첫 메일에서 멈췄다. 고침 탭이 그런 서버는 UID 없는 `MOVE`로 옮기게 고쳤다(0절 16 ①).
- 그 밖에 고친 것: `UIDPLUS`가 없을 때 90일 정리를 못 하면 하루 한 번 WARN과 문서(②), 본문 부분은 앞 1 MiB만 읽음(③, 30MB HTML이 힙 384MB의 앱을 내릴 수 있음), `privacy.md`의 `RATE_LIMITED` 기록(④). 글만 고친 것: 계정 상태를 읽는 때(⑤), 로그의 문의·댓글 ID(⑥, S12-AC-34). 고치지 않은 위험 넷은 `premortem.md` 7절.
- 테스트가 없는 기준: S12-AC-27(첨부 저장 오류)은 런타임 예외로 올라와 그 메일을 받는 폴더에 두고 시도 수를 늘린다. S12-AC-42는 `configuration.md` "Receiving email" 절, `privacy.md`, compose의 키 열로 충족한다. 릴리스 노트는 7절이다.

## 5. 브라우저

웹 탭(`popdesk:s12-i-web`, GreenMail SMTP·IMAP, 콘솔 1280·820px, 밝게·어둡게, 스크린샷 9장 `~\.claude\popdesk\tasks\shots\s12-i-web\`):
- 메일 한 통이 다음 회차에 문의가 됨, C-4 "받은 경로: 메일"
- C-18 켜짐과 마지막 성공, GreenMail을 멈춘 `CONNECT` 실패, 부분 205개 메일의 격리 1통
- C-7 `MAIL_IN_QUARANTINED`(누르면 `/system`), IMAP 없이 띄운 "설정 없음"과 `#receiving-email` 링크
- 가로 넘침 없음, 콘솔 오류 0

메인(`popdesk:test`, `POPDESK_SANDBOX=true`와 IMAP 키, Chrome DevTools MCP)은 웹 탭이 못 본 샌드박스 꺼짐을 봤다.

| 본 것 | 폭과 테마 | 결과 |
|---|---|---|
| C-18 메일 받기 구역 | 1280 밝게, 820 어둡게 | OIDC 구역 바로 뒤에 "메일 받기"와 `꺼짐(샌드박스)` 배지만. 가로 넘침 없음 |
| C-18 정리 작업 표 | 1280 밝게 | `mail-record-cleanup`이 메일 받기가 꺼진 채 돌았다(0절 15 ⑤) |
| 시작 로그 | – | 메일 받기가 꺼졌다는 WARN 한 줄, 주소 없음. 샌드박스 경고의 주소는 `s***@`로 가려짐 |

- 콘솔 오류: 로그인 전 401 둘.
- 스크린샷은 남기지 않았다(MCP가 작업 폴더 밖에 쓰지 못함). 설치는 `down -v`로 지웠다.
- 아무도 화면으로 보지 못한 것: "다시 시도할 메일" 줄, `MAIL_IN_STALLED`(한 시간), 마지막 실패 `AUTH`·`FOLDER`·`UNSUPPORTED`의 실제 서버 값. 잠긴 웹 테스트와 story가 본다.

## 6. 정한 것 (메인이 정함, 사용자 확인 전)

| 무엇 | 정한 것 | 까닭 |
|---|---|---|
| 잠긴 `SandboxMailTest`의 메일 종류 | 안내 종류 셋을 `NOT_IN_A_SANDBOX`로 따로 두고, 원본과는 둘을 합쳐 견줌 | 샌드박스는 메일 받기를 꺼서 안내가 생길 수 없다 |
| C-10 코드 점검 | 0절 16 ①~⑥으로 고치고, 넷은 남는 위험으로 둠 | 4절 |
| 단위 테스트가 찾은 둘 | 코드를 문서에 맞춤 | 기준 글(S12-AC-01, `BodyFit` Javadoc)이 분명함 |
| 꺼진 설치의 `quarantined90d` | 0 그대로 | 계약 글 "When off, … the counts `0`"(웹 탭이 물음) |

## 7. 릴리스 노트에 넣을 것

- 메일 받기(MAILIN). `IMAP_HOST`, `IMAP_USER`, `IMAP_PASSWORD`(`_FILE`), `MAIL_INBOUND_ADDRESS`가 다 있으면 켜진다. 1분마다 받는 폴더를 읽어 등록된 고객의 메일을 새 문의(채널 `EMAIL`)나 답장 댓글로 만들고, 처리한 메일은 처리됨 폴더, 격리는 격리 폴더로 옮긴다. 비밀번호 IMAP만 된다(XOAUTH2 없음, Microsoft 365는 안 됨, Google Workspace는 앱 비밀번호).
- 새 설정 키 `IMAP_*` 여덟과 `IMAP_PASSWORD_FILE`, `MAIL_INBOUND_ADDRESS`. 네 키 가운데 일부만 있거나, `IMAP_PORT`가 1~65535가 아니거나, `MAIL_INBOUND_ADDRESS`가 주소 하나가 아니거나 `+`가 있거나, 세 폴더에 빈 값이나 같은 값이 있으면 업그레이드 뒤 뜨지 않는다. `IMAP_PORT` 기본값 993.
- 보내는 모든 메일에 popdesk가 지은 `Message-ID`와 `Auto-Submitted: auto-generated`가 붙는다. 메일 받기가 켜지면 고객에게 가는 문의 메일에 `Reply-To: MAIL_INBOUND_ADDRESS`가 붙는다.
- 안내 메일 둘(문의를 만들지 못함, 댓글을 달지 못함), 15분에 한 통, `Auto-Submitted: auto-replied`. 자동 응답, `dmarc=fail`, 모르는 보낸 사람은 답장 없이 버린다.
- 경보 `MAIL_IN_STALLED`, `MAIL_IN_QUARANTINED`, 지표 `popdesk.mailin.messages`·`unauthenticated`·`round.failures`, 정리 작업 `mail-record-cleanup`, 시스템 상태 `mailIn`.
- 익명화가 받은 메일 기록의 보낸 주소를 비운다. 처리됨·격리 폴더의 메일은 90일 뒤 지운다(`UIDPLUS`가 없는 서버는 관리자가 지운다).
- `POPDESK_SANDBOX=true`는 메일 받기도 끈다. 0.2.0·0.3.0의 알려진 문제 "샌드박스가 받는 메일을 막지 않는다"를 지운다.
- 마이그레이션 V25(표 둘을 더함).

## 8. 넘긴 일

- 웹 탭이 본 것: 경보를 읽은 뒤 `/system`의 옆 메뉴 종에 "1"이 남아 있었다. 다음 조회 전 숫자인지 가리지 못했다. 알림함에는 그 한 건뿐이었다.
- `premortem.md` 7절의 남는 위험 넷(원문을 못 받는 메일이 회차를 막음, `COPY` 길이 끊김, 회차 스레드의 다른 `Error`, 저장소가 5분 넘게 멈춤).
- `AutomatedMail`이 주석 `(x)` 안의 글을 구분자로 볼지 기준에 적는다(지금은 코드대로 `fa(x)il`을 `fa`로 읽음).
