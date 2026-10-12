# 4번 묶음 설계

> 수용 기준(`acceptance.md`)을 어떻게 만들지 가운데, 나중에 바꾸기 어려운 것만 적는다. API 경로와 응답 모양은 계약 단계(C-4)의 컨트롤러 껍데기가 정한다.
> 묶음이 끝나면 남길 것은 `spec/architecture.md`, `adr.md`, `docs/`로 옮기고 이 파일은 `archive/`로 간다.
> 표시: [소스]는 레포에서 본 것, [추론]은 확인하지 않은 판단이다. [추론]은 7절의 실험이나 계약 탭이 확인한다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-12).

## 0. 메인이 정한 것

나중에 바꾸면 스키마나 이미 나간 계약, 대기 표에 남은 행이 틀어지는 것만 모았다. 동작에 관한 것은 `acceptance.md` 5절에 있다. 새 ADR은 없다.

| # | 정할 것 | 정한 것 | 까닭 | 버린 대안 |
|---|---|---|---|---|
| 1 | 알림함을 맡는 모듈 | `notification` 모듈 그대로다. 표 `notification`에 문의의 항목도 넣는다. `spec/architecture.md` 3절의 표(알림함을 `helpdesk`에 적은 줄)를 지금 코드에 맞게 고친다 | 표와 콘솔 API 셋, 관리자 항목이 이미 `notification`에 있다 [소스: `notification/ops/`, V3의 주석]. 한 사람의 알림함은 한 목록이라 표 둘로 가르면 쪽 나누기를 합쳐야 한다 | `helpdesk`에 문의 알림 표를 따로 두기 |
| 2 | "누구에게 무엇을"을 정하는 곳 | `helpdesk`의 한 클래스(문의 알림)가 일마다 받는 사람을 정하고, 정한 사람마다 `notification`의 공개 API 하나를 부른다. 그 API가 알림함 항목과 (메일이 있는 일이면) 메일 대기 행을 함께 넣는다 | 받는 사람의 판정(자기 제외, `ACTIVE`, 볼 수 있음, 멘션과 겹침)은 문의와 계정을 읽어야 하고 그것은 `helpdesk`의 것이다. 항목과 메일을 한 호출로 넣으면 둘의 받는 사람이 어긋날 수 없다(S4-AC-58) | 서비스마다 `mail.queue`와 알림함 넣기를 따로 부르기. 지금 `CommentService`가 그렇게 하고 있고, 일이 여덟으로 늘면 어긋난다 |
| 3 | 알림함이 문의를 볼 수 있는지 거르는 법 | `notification`은 `helpdesk`의 표를 읽지 않는다. 그 사람의 항목이 가리키는 문의 ID들을 모아 `helpdesk`의 공개 API("이 계정이 이 가운데 볼 수 있는 것")에 묻고, 돌아온 ID로 목록과 수를 거른다. 안 읽은 수는 안 읽은 항목의 문의만 묻는다 | 두 모듈은 서로의 표를 읽지 않는다 [소스: `architecture.md` 3절]. 보내기 직전의 확인이 이미 같은 길(`IssueAccess`)을 쓴다 [소스: `MailSender`]. 쪽 나누기와 건수가 맞으려면 거르기가 SQL의 조건이어야 한다 | 알림함 쿼리가 `issue`와 소속 표를 바로 잇기 / 한 쪽을 읽은 뒤 코드로 빼기(건수가 틀린다) |
| 4 | 항목의 칸 | `notification`에 `issue_id uuid`, `comment_id bigint`, `actor_id bigint`를 더한다. 모두 `null`을 받는다. 제목과 키는 넣지 않고 읽을 때 문의에서 읽는다 | INBOX-1의 "지금 제목". 익명화(10번)와 영구 삭제(7번)가 문의와 계정 쪽만 고치면 된다. 표에는 외래 키가 없다 [소스: V3, V8의 주석] | 제목을 항목에 복사 |
| 5 | 알림함의 종류 이름 | `STAFF_COMMENT`, `CUSTOMER_COMMENT`, `ISSUE_RESOLVED`, `ISSUE_CLOSED`, `ISSUE_REOPENED`, `ISSUE_FILED_ON_BEHALF`, `MENTIONED`, `ASSIGNED`. 있던 셋(`DELIVERY_FAILED`, `ATTACHMENT_QUOTA_WARNING`, `MEMBERSHIP_REQUESTED`)은 그대로다. 누가 일으켰는지는 종류가 아니라 일으킨 사람 칸으로 안다 | 이름은 계약과 표에 들어가 바꾸지 못한다. 메일과 웹훅의 종류 이름과 맞춘다 [소스: `MailKind`, `WebhookKind`] | `ISSUE_CLOSED_BY_CUSTOMER`처럼 방향마다 종류 두기 |
| 6 | 메일의 종류 이름 | `ISSUE_RESOLVED`, `ISSUE_CLOSED`, `ISSUE_REOPENED`, `MENTIONED`, `ASSIGNED`, `MEMBERSHIP_REQUESTED`. 있던 넷은 그대로다 | 대기 표의 `kind`에 들어간다. 올리는 중에 대기에 남은 행이 있을 수 있다. `kind`에는 값 제약이 없다 [소스: V3] | – |
| 7 | 소속 요청 메일이 고객사를 아는 법 | `outbox`에 `company_code text`를 더한다(`null` 허용). 소속 요청 메일의 행만 채운다 | 대기 행에는 문의와 댓글 칸뿐이다 [소스: V3]. 알림함 표도 V8에서 같은 칸을 더했다 | 요청 ID를 넣기. 보낼 때 요청이 이미 닫혔으면 고객사를 읽지 못한다 |
| 8 | 댓글의 고침과 지움을 두는 곳 | `comment`에 `edited_at timestamptz`, `deleted_at timestamptz`, `deleted_by bigint`를 더한다. 지우면 `content`를 빈 글로 바꾼다. 지운 표시는 `deleted_at`이 있는가다 | COM-7의 꼴이고 첨부가 이미 그렇게 한다 [계약 탭이 첨부의 칸을 보고 이름을 맞춘다]. 고친 이력(옛 내용)은 두지 않는다 | 옛 내용을 표에 남기기. 지운 개인정보가 남는다 |
| 9 | 멘션을 두는 곳 | 새 표 `comment_mention`: `tenant_id`, `comment_id`(댓글), `account_id`(계정), `created_at`. 기본 키 `(comment_id, account_id)`. 댓글을 지워도 멘션 행은 둔다(S4-AC-26의 목록은 그대로다) | 계정으로 찾아야 한다(익명화, "아직 멘션돼 있는가"). 댓글 행의 배열 칸이면 외래 키를 걸 수 없다 | `comment.mentions bigint[]` |
| 10 | 새 오류 코드 | 400 `COMMENT_DELETED`. `fields`의 code `TOO_MANY`(목록의 개수가 상한을 넘음). 멘션 대상이 맞지 않는 것은 `INVALID_INPUT`과 `fields`의 `UNKNOWN_VALUE`다 | 코드는 공개 계약이다. 계약 탭이 `conventions.md` 표에 먼저 더한다 | – |
| 11 | 마이그레이션 | V12 하나(SQL). 칸과 표와 색인만 더하고 있던 행을 고치지 않는다 | 계산할 것이 없다. 있던 댓글과 항목은 새 칸이 모두 `null`이다(S4-AC-72) | – |

## 1. 데이터 (마이그레이션 V12)

| 바뀌는 것 | 내용 | 까닭 |
|---|---|---|
| `comment` | 칸 셋: `edited_at timestamptz`, `deleted_at timestamptz`, `deleted_by bigint`(계정) | 0절 8 |
| 새 표 `comment_mention` | 0절 9의 칸. 색인 `(account_id)` | 멘션된 사람으로 찾기 |
| `notification` | 칸 셋: `issue_id uuid`, `comment_id bigint`, `actor_id bigint`. 색인: `(account_id) WHERE read_at IS NULL`, `(comment_id) WHERE comment_id IS NOT NULL`, `(issue_id) WHERE issue_id IS NOT NULL` | 0절 4. 안 읽은 수를 60초마다 센다. 댓글을 지울 때 그 댓글의 항목을 찾는다. 7번의 영구 삭제가 문의의 항목을 찾는다 |
| `outbox` | 칸 하나: `company_code text` | 0절 7 |

- 표 하나가 늘어나므로 `deploy/upgrade/make-sample.py`가 채운다(S4-AC-73). 멘션이 있는 댓글 하나, 고친 댓글 하나, 지운 댓글 하나, 읽은 항목과 안 읽은 항목이 견본에 들어간다.
- 옛 이미지는 이 스키마에서 뜨지 않는다(D-46). 릴리스 노트에 적는다.
- 알림함 항목에는 보관 기간이 없다(보관 표: 문의를 영구 삭제할 때 함께 지운다). 한 사람의 항목이 수천 건으로 늘 수 있다. 0절 3의 길이 그 크기에서 얼마나 걸리는지 7절 실험 3으로 잰다.
- 항목과 대기 행에는 글(제목, 댓글 내용, 주소)을 넣지 않는다. 잠긴 `notify.MailQueueRowTest`와 `QueueTransactionTest`가 "제목과 댓글 글이 든 표에 대기 표가 없다"를 본다 [소스].

## 2. 댓글을 바꾸는 요청의 꼴

한 트랜잭션이고 차례는 `architecture.md`의 잠금 순서를 따른다.

| 요청 | 차례 |
|---|---|
| 고치기 | ① 범위 안에서 문의와 댓글을 읽는다(없거나 고객에게 내부 댓글이면 404) ② 문의 행을 `FOR NO KEY UPDATE`로 잠근다(달기와 같다 [소스: `CommentService.add`의 `lockByKey`]) ③ 쓴 사람인지, 소속이 남았는지(403) ④ 입력(400) ⑤ 지운 댓글인지(`COMMENT_DELETED`), 문의가 `CLOSED`인지(`ISSUE_CLOSED`) ⑥ 내용이 바뀌었으면 UPDATE와 `edited_at` ⑦ 멘션 목록이 왔으면 견줘 더하고 빼고, 새로 더한 사람에게 알린다. 빠진 사람의 안 읽은 멘션 항목은 지운다 |
| 지우기 | ①~③ 같다(관리자는 남의 것도 된다) ④ 이미 지웠으면 그대로 답한다 ⑤ 댓글의 첨부를 첨부 지우기와 같은 길로 지운다(잠금 순서 2) ⑥ `content`를 비우고 `deleted_at`, `deleted_by` ⑦ 그 댓글의 알림함 항목을 지운다 |

- 확인의 차례는 `permissions.md`의 것이다: 범위(404) → 권한(403) → 입력(400) → 상태(400). 잠긴 `ops.CheckOrderTest`가 새 경로도 훑는다 [추론: 계약 탭이 확인].
- 고치기는 `version`을 받지 않는다. 댓글은 쓴 사람 혼자 고치므로 뒤에 온 것이 남는다.
- 멘션만 바뀌고 내용이 같으면 `edited_at`은 그대로다(S4-AC-06).
- 고객의 요청은 포털 타입으로만 받는다. 포털의 댓글 타입에는 `content`뿐이다. 포털 본문에 `mentions`가 와도 모르는 칸이라 무시한다(등록과 같다 [소스: 3번 `design.md` 2절]).
- 멘션 대상의 자격은 문의 행을 잠근 뒤 같은 트랜잭션에서 읽는다. 계정 행과 배정 행은 잠그지 않는다(담당자 쓰기와 같은 까닭이다 [소스: `architecture.md` 잠금 순서]).
- 멘션 후보와 담당자 후보는 같은 조건(`ACTIVE` 관리자와 그 고객사에 배정된 `ACTIVE` 상담원)을 쓴다 [소스: 3번의 후보 목록]. 멘션은 여기에 올린 고객(공개 댓글)을 더하고 자기를 뺀다. 조건을 한 곳에 둔다.

## 3. 알림을 넣는 길

`helpdesk`의 문의 알림 클래스 하나가 아래 표를 가진다. 서비스는 "이 문의에 이 일이 났다, 한 사람은 누구다"만 넘긴다.

| 일 | 받는 사람 | 항목 | 메일 |
|---|---|---|---|
| 상담 쪽의 공개 댓글 | 올린 고객 | `STAFF_COMMENT` | 있던 `STAFF_COMMENT` |
| 고객의 댓글 | 담당자 | `CUSTOMER_COMMENT` | – |
| 상담 쪽의 `RESOLVED` | 올린 고객 | `ISSUE_RESOLVED` | `ISSUE_RESOLVED` |
| 상담 쪽의 `CLOSED` | 올린 고객 | `ISSUE_CLOSED` | `ISSUE_CLOSED` |
| 고객의 종료 | 담당자 | `ISSUE_CLOSED` | – |
| 상담 쪽의 재오픈 | 올린 고객 | `ISSUE_REOPENED` | `ISSUE_REOPENED` |
| 고객의 재오픈 | 담당자 | `ISSUE_REOPENED` | – |
| 담당자 정하기·바꾸기 | 새 담당자 | `ASSIGNED` | `ASSIGNED` |
| 대리 등록 | 올린 고객 | `ISSUE_FILED_ON_BEHALF` | 있던 `ISSUE_FILED_ON_BEHALF` |
| 멘션 | 멘션된 사람 | `MENTIONED` | `MENTIONED` |
| 소속 요청(있던 길) | `ACTIVE` 관리자 전부 | 있던 `MEMBERSHIP_REQUESTED` | `MEMBERSHIP_REQUESTED` |

- 받는 사람을 거르는 차례: ① 한 사람 자신이면 뺀다 ② `ACTIVE`가 아니면 뺀다 ③ 그 문의를 볼 수 없으면 뺀다(있던 `IssueVisibility` [소스]) ④ 같은 요청에서 멘션도 받으면 멘션만 남긴다.
- 있던 두 메일(`STAFF_COMMENT`, `ISSUE_FILED_ON_BEHALF`)은 이 길로 옮기되 지금 보내는 조건을 바꾸지 않는다. 지금은 넣을 때 받는 사람의 상태를 보지 않고 보낼 때 본다 [소스: `MailSender`]. ②와 ③을 넣을 때 보면 "정지된 고객의 대기 행이 생겼다가 보낼 때 버려진다"를 보는 잠긴 테스트(`MailQueueRowTest`, `KoreanOnBehalfMailTest`)와 부딪힐 수 있다 [추론]. 그래서 **메일 대기 행은 지금처럼 넣고 보낼 때 거르며, ②와 ③은 알림함 항목에만 쓴다.** 새 메일도 같은 꼴로 맞춘다. S4-AC-36은 항목의 기준이고 S4-AC-54가 메일의 기준이다. 계약 탭이 두 테스트를 읽고 이 문단을 확정한다(7절 실험 2).
- 항목과 대기 행은 UPDATE가 행을 바꾼 뒤에만 넣는다. 상태 변경은 문의 행을 먼저 잠그지 않는다 [소스: `architecture.md`]. 넣는 자리는 지금 웹훅 대기 행을 넣는 자리다 [소스: `IssueService.move`].
- 새 INSERT(멘션 행, 항목, 대기 행)는 잠금 순서 3의 자리다. 항목 지우기(댓글 지우기, 멘션 빼기)는 문의 행을 쥔 채 `notification` 행을 지운다. 읽음 처리는 문의 행을 잡지 않으므로 엇갈리지 않는다.
- 담당자가 한 일은 담당자에게 가지 않는다(①). 담당자가 관리자이고 배정이 없어도 관리자는 모든 문의를 보므로 ③을 지난다.
- 소속 요청은 문의가 없다. 있던 `AdminInbox.membershipRequested`가 항목을 넣는 자리에서 관리자마다 메일 대기 행도 넣는다.

## 4. 알림함을 읽는 길

- 목록: ① 그 계정의 항목이 가리키는 문의 ID를 겹치지 않게 모은다 ② `helpdesk`에 볼 수 있는 것을 묻는다 ③ `issue_id IS NULL OR issue_id = ANY(볼 수 있는 것)`으로 쪽과 건수를 읽는다 ④ 그 쪽의 문의 키·제목·고객사와 일으킨 사람의 이름을 `helpdesk`에서 읽어 붙인다.
- 안 읽은 수: ①을 안 읽은 항목으로만 한다.
- 포털과 콘솔의 컨트롤러와 응답 타입을 따로 둔다(D-41). 포털의 변환은 일으킨 사람을 `PortalIssueController.person`과 같은 함수로 바꾼다 [소스]. 상담 쪽 사람이면 "지원팀" 표시만 남는다.
- 포털 타입의 종류 enum은 고객에게 가는 다섯(`STAFF_COMMENT`, `ISSUE_RESOLVED`, `ISSUE_CLOSED`, `ISSUE_REOPENED`, `ISSUE_FILED_ON_BEHALF`)과 `MENTIONED`다. 고객 계정의 항목에는 다른 종류가 생기지 않는다(3절 표). 그래도 포털 목록은 이 종류만 읽는 조건을 SQL에 둔다(관리자 항목이 섞일 길을 막는다).
- 종류를 enum으로 바꾸는 곳이 모르는 이름에서 500을 낸다 [소스: `ConsoleNotificationController`의 `valueOf`]. 새 종류를 enum에 더한다. 옛 이미지로 내려갈 일은 없다(D-46).
- 모두 읽음은 그 계정의 안 읽은 항목 전부를 한 문장으로 바꾼다. 지금 보이지 않는 항목도 읽음이 된다(다시 보이게 됐을 때 옛 항목이 안 읽음으로 쌓이지 않는다).

## 5. 메일

- 새 메일은 있던 메일과 같은 길이다: 대기 행에 받는 계정과 문의(와 댓글)만 두고, 제목과 주소는 보낼 때 읽고, 보내기 직전에 다시 본다 [소스: `MailSender.attempt`].
- 보내기 직전의 확인에 둘을 더한다: 행에 댓글이 있으면 그 댓글이 지워지지 않았는가(S4-AC-55), 종류가 `MENTIONED`이면 받는 사람이 아직 그 댓글에 멘션돼 있는가(S4-AC-56). `notification`이 `helpdesk`의 공개 API로 묻는다.
- 문의 링크는 받는 사람의 역할로 고른다. 지금 문의 메일 둘은 포털 주소가 박혀 있다 [소스: `MailSender`]. 초대 메일이 쓰는 `Recipient.app`을 같이 쓴다.
- 글은 `MailText`에 한국어와 영어로 더한다. 제목에 `[문의 키]`와 문의 제목(한 줄로 바꾼 것)을 넣고 본문에는 제목과 링크만 넣는다. 댓글 내용과 문의 내용은 넣지 않는다(S4-AC-53).
- 멘션 메일은 받는 사람이 상담 쪽이면 멘션한 사람의 이름을 넣지 않는다. "문의 KEY의 댓글에서 회원님을 언급했습니다"만 쓴다. 대기 행에 일으킨 사람 칸이 없고, 고객에게 가는 것과 글을 하나로 둔다.
- 소속 요청 메일은 문의가 없다. 고객사 코드와 콘솔의 고객사 링크만 쓴다. 보내기 직전에는 받는 사람이 `ACTIVE` 관리자인지만 본다.

## 6. 쓸 컴포넌트와 토큰

`screens.md`에 화면마다 적는다. 여기는 공용 컴포넌트의 변화만 적는다.

- 새 토큰은 없다.
- `CommentItem`: 고침 표시, 멘션된 사람의 이름, 동작 버튼 자리를 더한다. 지운 댓글의 표시는 이미 있다 [소스: `CommentItem.tsx`의 `deleted`, `deletedLabel`]. 있던 props는 깨지 않는다.
- `AppShell`: 머리에 알림함 링크와 안 읽은 수의 자리를 더한다. 옆 메뉴의 수(`count`, `countLabel`)와 같은 꼴로 받는다 [소스: `AppShell.navCount.test.tsx`].
- 새 공용 컴포넌트 하나: 여러 사람 고르기(이름표, 고른 사람 목록, 상한). 콘솔의 "알릴 사람"이 쓴다. 기성 부품의 모양을 가져오지 않는다.
- 있는 것을 쓴다: `MarkdownEditor`, `ConfirmDialog`, `RelativeTime`, `Avatar`, `Alert`, `Toast`, `Pagination`, `EmptyState`.
- 안 읽은 수를 주기적으로 받는 것은 옆 메뉴의 소속 요청 수가 쓰는 길을 따른다 [소스: `navCount.ts`의 `useNavCountRefresh`].

## 7. 계약 앞 실험

계약 탭(C-4 서버)이 껍데기를 쓰기 전에 같이 본다. 결과는 보고에 표로 적는다.

| # | 볼 것 | 까닭 |
|---|---|---|
| 1 | V12를 0.3.0 견본에 올렸을 때, 표를 꼴로 찾는 테스트 도움(`grep -rln "db.tables()" server/src/test`)을 쓰는 클래스와 "요청 뒤 더해진 행은 이것뿐"을 단언하는 클래스(`notify.NoWebhookTest`, `QueueTransactionTest`, `MailQueueRowTest`)가 새 표와 알림함 행에서 지나는지 | 2번·3번 C-11 규칙. 댓글과 상태 변경이 이제 `notification`에 행을 넣는다 |
| 2 | 있던 메일 둘의 받는 사람을 넣을 때 거르면 깨지는 잠긴 테스트가 있는지(`MailQueueRowTest`의 "정지 뒤에는 버림", `KoreanOnBehalfMailTest`, `QueuedMailBoundaryTest`) | 3절의 굵은 문단을 확정한다 |
| 3 | 한 계정에 항목 10,000건(문의 2,000건)을 넣고 목록 첫 쪽과 안 읽은 수가 걸리는 시간 | 0절 3. 100 ms를 넘으면 메인이 길을 다시 정한다 |
| 4 | 앞 묶음의 잠긴 테스트와 숨긴 E2E 가운데 문의의 상태나 담당자를 바꾼 뒤 메일 수나 알림함의 항목 수를 단언하는 곳 | 새 메일과 항목이 그 수를 바꾼다. 테스트 쪽 탭이 맞출 목록이 된다. 지금 아는 것은 `manager.MembershipRequestMailTest`의 "관리자에게 메일 없음"이다 |
| 5 | 계약을 합친 뒤 `project.NoStoreTest`, `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `boundary.BoundaryListTest`가 새 경로에서 지나는지 | 1b C-11 규칙 |
| 6 | 첨부의 지운 흔적이 포털과 콘솔 응답에서 어떤 칸으로 나가는지 | 댓글의 지운 흔적을 같은 이름으로 맞춘다(0절 8) |
| 7 | `ops.OpsAlertTest`와 `ErrorBodyTest`의 "새 상담원의 알림함은 비었다"가 3절의 길에서도 맞는지(픽스처가 그 상담원을 담당자로 정하지 않는지) | 잠긴 테스트 |

## 8. 일하는 차례

1. C-4 서버 계약: V12 없이 컨트롤러 껍데기와 요청·응답 타입(501), `conventions.md`의 새 코드, 계약 파일과 생성 클라이언트. 7절의 실험.
2. 계약을 합친 뒤 앞 묶음의 훑는 테스트를 돈다. 계약과 새 기준에 묶인 잠긴 테스트는 테스트 쪽 탭이 맞춘다.
3. C-4 웹 껍데기: 알림함 화면 둘, 머리의 링크, 댓글의 고치기·지우기와 "알릴 사람", story.
4. C-5 테스트: 서버 셋(① 댓글 고치기·지우기와 멘션 ② 알림함 넣기와 읽기, 메일 ③ 관문: 경계, 상담 쪽 이름, 업그레이드, 견본, 로그). 한 번에 하나씩 띄운다. 웹 둘(콘솔, 포털), 숨긴 E2E 하나.
5. C-6 실패 확인과 잠금, C-7 점검(서버, 웹), C-8 작업 분해.
6. C-9 구현: 서버는 둘로 나누는 것을 추천한다(① V12, 댓글 고치기·지우기, 멘션과 후보 ② 문의 알림 클래스, 알림함 API, 새 메일, 소속 요청 메일, 견본 생성기). C-8에서 잠긴 테스트의 픽스처가 어느 쪽 코드에 기대는지 보고 확정한다. 웹은 콘솔과 포털 하나씩이다.
7. C-10 검증. 코드 푸시는 여기서 한 번이다.
