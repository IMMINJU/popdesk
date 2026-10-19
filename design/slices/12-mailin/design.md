# 12 묶음 설계

> `acceptance.md`의 기준을 어떻게 만드는지 적는다. 조사는 메인 세션과 서브에이전트가 했다(2026-10-19). [소스]는 지금 코드에서 읽은 것, [추론]은 읽지 않고 짐작한 것이다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-19).

## 0. 메인이 정한 것

| # | 정한 것 |
|---|---|
| 1 | 코드는 `helpdesk.mailin`에 둔다. 문의·댓글·첨부·계정·고객사 서비스를 부르는 쪽이라 `helpdesk` 안이어야 한다. `helpdesk`는 이미 `notification`을 부른다 [소스: 11 설계 0절 8]. 새 클래스: `MailInSettings`(설정 읽기와 시작 검사, `InitializingBean`에서 던짐), `MailInLoop`(`SmartLifecycle`, 1초마다 깨서 앱 시계로 1분을 잼, 인스턴스 잠금을 쥘 때만), `ImapMailbox`(연결, 훑기, 옮기기, 폴더 만들기), `MailInProcessor`(메일 하나 → 결과), `MailReceivedRepository`, `MailInStatus`(메모리 수와 DB 수), `MailPartFile`(메일 부분을 `MultipartFile`로), 순수 계산 `AutomatedMail`, `IssueKeyInSubject`, `InboundAddress`, `HtmlText`, `QuoteTrim`, `BodyFit` |
| 2 | 보내는 쪽(`notification.outbox`): `SmtpMail.deliver`가 `Message-ID`를 짓고(`MimeMessage.updateMessageID`를 덮는 작은 하위 클래스 [추론: Angus는 `saveChanges` 때 `Message-ID`를 새로 짓는다. 덮지 않으면 단 값이 바뀐다]), `Auto-Submitted`와 조건에 맞으면 `Reply-To`를 달고, SMTP가 받은 뒤 `mail_sent`에 넣는다. 대기 행에 이미 `issue_id`가 있다 [소스: V3 `outbox.issue_id uuid`, `OutboxMailQueue.java:36`]. `SmtpMail.deliver`는 문의도 받는 앱도 모르므로 [소스: 안정성 검토, `SmtpMail.java:120`] 머리글은 `MailSender`가 정해 넘기고(`row.issueId()`, `recipient.app()` [소스: `MailSender` 94·129줄]), `Attempt`(지금 `record Attempt(Outcome, String errorKind)` [소스: C-4 보고])가 지은 `Message-ID`를 들고 나오게 하고, `OutboxDelivery.sent`의 트랜잭션이 커밋된 뒤 따로 짧은 트랜잭션으로 `insert … select … where exists (문의)`를 한다. 실패하면 로그 한 줄(기록 ID와 예외 클래스 이름)만 남긴다. `attempt` 안이나 `sent`의 트랜잭션 안에서 넣다 예외가 나면 대기 행이 남아 메일이 두 번 나간다 [소스: `SendLoops.attempt` 148-155줄, `sendDue` 96-102줄]. 기록을 못 남겨도 답장은 제목 키로 이어진다(C-4 질문 3, 메인이 정함). `Reply-To` 조건: 메일 받기가 켜짐, `issueId != null`, 받는 앱이 포털, 안내 메일이 아님. 새 클래스 `MailSentRepository`(넣기, `Message-ID` 목록으로 문의 찾기, 오래된 것 지우기)는 `notification.outbox`에 두고, `helpdesk.mailin`은 `notification`의 공개 인터페이스(`SentMailLookup` 꼴)로 찾는다 |
| 3 | 시스템 상태: `SystemStatus`에 `mailIn` 칸을 더한다. 컨트롤러는 `notification` 쪽이므로 `notification`에 인터페이스(`MailInState` 꼴)를 두고 `helpdesk.mailin.MailInStatus`가 구현한다(11의 `OidcSignInState`와 같은 꼴, 새 순환 없음) |
| 4 | 경보: `OpsAlerts`에 `MAIL_IN_STALLED`, `MAIL_IN_QUARANTINED`를 더하고 `web/console/NotificationKind`의 거울에도 더한다(C-4에서 함). 둘 다 `helpdesk.mailin`이 직접 내고 푼다(`OpsAlerts` 머리말 "다른 종류는 조건을 아는 모듈이 내고 푼다" [소스: C-4 보고]). `OpsAlertService.review`의 보내기 STALLED는 DB 상태로 매번 다시 판정하지만 [소스: 76-96줄] 메일 받기의 실패 시작은 메모리 값이라 그 꼴을 쓰면 다시 뜬 뒤 첫 검토가 경보를 푼다. 그래서: 회차 끝에 실패가 시작된 지 한 시간이 지났으면 낸다, 회차가 성공하면 푼다, 실패만으로는 풀지 않는다(앱이 다시 떠도 경보가 남는다). `MAIL_IN_QUARANTINED`는 격리를 커밋할 때 내고, 하루 한 번 회차 끝에 마지막 격리가 24시간 전이면 푼다(C-4 질문 1, 메인이 정함) |
| 5 | 문의 만들기: `IssueService.prepare`가 채널을 `CHANNEL_PORTAL`로 박아 둔다 [소스: `IssueService.java:72, 228`]. 채널을 받는 메서드(`prepare(Viewer, NewIssue, String, String channel)` 꼴)를 더하고 포털은 `PORTAL`로 부른다. 멱등 키는 `null`이다(메일은 받은 메일 기록이 그 일을 한다) |
| 6 | 서비스는 `Viewer(accountId, CUSTOMER)`만 받고 HTTP를 보지 않는다 [소스: `Viewer.java:12`]. 다만 계정이 `ACTIVE`인지는 세션 필터가 보던 일이라 서비스가 다시 보지 않는다 [소스: 서브에이전트 조사]. `MailInProcessor`가 서비스를 부르기 앞에 계정 행을 읽어 상태와 역할을 본다(포털 세션 필터와 같은 틈, 16 ⑤) |
| 7 | 볼 수 있는지는 포털이 쓰는 문의 읽기(`Viewer` 범위의 조회, 없거나 볼 수 없으면 404 `ApiException`)로 가린다. `mail_sent`로 찾은 문의도 `findById(viewer, …)` 꼴의 범위 조회로 읽는다(`ScopedReadRulesTest` [소스: 안정성 검토]). 댓글을 달 수 있는지는 `CommentService.add`가 내는 오류(`ReporterAccess.writes`, `ISSUE_CLOSED`)로 가린다 [소스: `CommentService.java:111`, `ReporterAccess.java:24`]. 서비스가 저마다 새 트랜잭션을 열고(`Transactions`가 `REQUIRES_NEW`, "Never call it inside a transaction" [소스: `Transactions.java:16, 27`]) 오류면 그것만 되돌아가므로 `rollbackOnly` 걱정은 없다. 서비스를 `MANDATORY`나 `@Transactional`로 바꾸지 않는다 |
| 8 | 첨부: 새 문의는 포털과 같이 `AttachmentService.countFiling` → `writeFiling`(`pending` 행 커밋, 저장소에 씀) → 문의 트랜잭션 안의 `storeFiled`로 간다 [소스: `AttachmentService.java:203-258`]. `writeFiling`은 첫 문제에서 묶음 전체를 거절하므로 [소스: 안정성 검토, `checkSize`·합계·설치 한도 차례] 규칙(파일 하나 10MB, 20개, 50MB, 설치 한도 `storedBytes`, 이름)은 `MailInProcessor`가 같은 상수로 먼저 걸러 이름과 까닭을 모은다. 그래도 서비스가 거절하면 S12-AC-09의 오류다. 댓글 첨부는 지금 흐름(`upload(..., commentId)`가 파일마다 따로 커밋 [소스: 144-196줄])으로는 댓글과 한 트랜잭션이 안 되므로, 댓글 트랜잭션 안에서 문의 잠금 뒤에 문의의 공개 첨부 합계(`publicBytes`)를 보고 `comment_id`까지 쓰는 메서드를 더한다(`pending` 행은 앞서 커밋한다). `MailPartFile`은 부분을 임시 파일로 풀어 실제 길이를 준다(`writePending`이 `getSize()`를 쓰기 길이로 씀 [소스: 597-600줄]). 파일 형식 제한은 지금 없다 [소스: 627-636줄] |
| 9 | 메일 하나의 차례: ① 크기(`RFC822.SIZE`, `FetchProfile`에 UID와 함께)가 30MB를 넘으면 격리 ② 원문(`BODY.PEEK[]`)을 받아 열쇠 계산, MIME 부분 수와 깊이 확인 ③ 기록 찾기: 결과가 있으면(`DMARC_FAIL`로 버린 것 빼고) 그 폴더로 옮기기만 ④ 시도 수가 이미 5면 격리. 아니면 +1을 따로 커밋(행이 없으면 넣음, `ON CONFLICT DO UPDATE`) ⑤ 버리기(2.4의 차례, 짧은 트랜잭션 하나로 결과 씀) ⑥ 첨부 `pending` ⑦ 서비스를 부른다. 결과 쓰기(`UPDATE mail_received SET outcome … WHERE id = ? AND outcome IS NULL`, 0행이면 예외로 되돌림)는 서비스 트랜잭션 안의 훅에서 한다: 문의는 `file(viewer, draft, Runnable)`의 훅 [소스: `IssueService.java:347, 359`]. 훅이 `issues.insert`보다 먼저 돌므로 `mail_received.issue_id` 외래 키를 `DEFERRABLE INITIALLY DEFERRED`로 둔다(`attachment.issue_id`가 이미 이 꼴 [소스: 안정성 검토, V2 95줄]). 댓글은 `add`에 훅이 없으므로 `comments.insert` 뒤에 부르는 `LongConsumer`를 받는 오버로드를 더한다 ⑧ 서비스가 `ApiException`을 내면 그 코드로 결과를 고른다: 429는 `RATE_LIMITED`(요청 수는 서비스가 이미 센다 [소스: `IssueService.java:207`, `CommentService.java:113`, `AttachmentService.java:208`]. 따로 세지 않는다), 댓글을 못 다는 오류는 안내 B. 안내 결과와 안내 대기 행은 짧은 트랜잭션 하나로 쓴다. 안내 A가 될 메일은 서비스를 부르지 않으므로 `AccountRateLimit.count(ISSUE, …)`를 한 번 부른다 ⑨ 옮기기. ⑦에서 앱·DB 오류가 나면 첨부를 `discardEnded`로 치우고 옮기지 않는다 |
| 10 | HTML을 글로 바꾸는 데 `org.jsoup:jsoup`(버전 고정)을 더한다(D-67). 손으로 짠 파서는 깨진 HTML과 문자 참조에서 틀리기 쉽다. MIT, jar 하나(0.5MB쯤 [추론]), 따라오는 것이 없다 [추론: C-4 탭이 `dependency:tree`로 확인]. 빌드 파일이 바뀌므로 합칠 때 메인이 본다 |
| 11 | 안내 메일은 새 대기 종류 `MAIL_IN_NOTICE_NO_ISSUE`(A), `MAIL_IN_NOTICE_CLOSED`, `MAIL_IN_NOTICE_NOT_ALLOWED`(B 둘. 대기 행에 까닭을 담을 칸이 없어 종류로 나눈다 [소스: 안정성 검토]. 대기 행의 `issue_id`는 B만)로 `MailText`에 문구를 더한다. 15분에 한 통은 대기 행을 넣기 전에 메모리(계정 ID → 마지막 시각, 앱 시계)로 본다. 앱이 하나라 메모리면 된다(D-23) |
| 12 | 익명화: `AnonymizationService.anonymize`의 트랜잭션 안에서 `mailReceived.clearSender(accountId)`(그 계정의 기록의 `sender_address`를 `NULL`로). 영구 삭제는 문의 행을 지우지 않고 내용만 비운다(`TrashService.empty`가 딸린 행을 지우고 `IssueRepository.updatePurged` [소스: C-4 보고, 302-316줄, 792줄]). 그래서 `empty()` 안 `mail.dropAbout(id)` 옆에서 `mail_sent` 행을 지우고 `mail_received`의 `issue_id`를 비운다(`comment_id`는 댓글을 지울 때 `ON DELETE SET NULL`이 돈다) (C-4 질문 2, 메인이 정함) |
| 14 | IMAP: 993은 프로토콜 `imap`에 `mail.imap.ssl.enable=true`로 연다(`imaps`로 열면 `mail.imaps.*`만 읽혀 시간 제한이 빠진다 [추론: 안정성 검토]). 쓸 속성: `mail.imap.connectiontimeout=10000`, `mail.imap.timeout=30000`, `mail.imap.writetimeout`, `mail.imap.starttls.enable`·`required`, `mail.imap.ssl.checkserveridentity=true`, `mail.imap.peek=true`, `mail.debug=false`. 속성 만들기는 순수 계산(`MailInSettings`)에 두어 단위 테스트로 본다. 옮기기는 `hasCapability("MOVE")`면 `moveUIDMessages`, 아니면 `UIDPLUS`가 있을 때 `COPY`·`\Deleted`·`UID EXPUNGE`, 둘 다 없으면 `UNSUPPORTED`. 회차 스레드는 `scheduleWithFixedDelay`라 잡지 못한 오류 하나에 멈추므로, 메일 하나의 처리를 감싸 `RuntimeException`과 `StackOverflowError`를 이름으로 잡는다(`catch (Throwable)`은 `SwallowedExceptionsTest`가 막는다 [소스: 안정성 검토]) |
| 13 | 정리 작업 `mail-record-cleanup`은 `helpdesk.mailin`에 `CleanupJob`으로 두고 두 표를 지운다(`mail_sent`는 `SentMailLookup`의 지우기로). 하루 한 번, 한 번에 지우는 행 수에 상한을 두지 않는다(90일치 기록이라 작다 [추론]) |
| 15 | C-7 서버 점검에서 정한 것(2026-10-19, 메인이 정함): ① `lastSuccessAt`은 그 회차의 메일(100통까지) 처리와 옮기기, 경보 판정, 하루 한 번 메일함 정리까지 마친 뒤에 쓴다(모든 테스트가 이 값을 보고 바로 결과를 읽는다). 계약 설명도 그렇게 고친다 ② 받는 폴더의 지움 표시 메일은 100통에 세지 않는다 ③ 샌드박스인데 `IMAP_*`가 없으면 `offReason`은 `NOT_SET`이고 메일 받기 시작 경고를 내지 않는다(설정이 있을 때만 `SANDBOX`와 경고) ④ `quarantined90d`와 `MAIL_IN_QUARANTINED`는 모든 격리(`QUARANTINED`의 까닭 `FAILED`·`TOO_LARGE`·`TOO_COMPLEX`와 `RATE_LIMITED`)를 센다. 계약 설명(`conventions.md`, `MailInStatus.quarantined90d`)을 고친다 ⑤ `mail-record-cleanup`은 메일 받기를 꺼도 돈다(잠긴 `CleanupStall.JOBS`가 꺼진 컨텍스트에서 이 작업을 기다린다) ⑥ `mail_sent.message_id`는 꺾쇠를 떼고 저장하고, 받은 `In-Reply-To`·`References`의 값도 꺾쇠를 떼고 견준다 |
| 16 | C-10 코드 점검에서 정한 것(2026-10-19, 메인이 정함): ① `MOVE`는 있고 `UIDPLUS`가 없는 서버에서는 UID 없는 `MOVE`(`moveMessages`)로 옮긴다. `UID MOVE`는 `UIDPLUS`가 있을 때만 ② `UIDPLUS`가 없으면 90일 정리는 하지 않고, 하루 한 번 WARN을 남기며 `configuration.md`·`privacy.md`에 적는다(남이 지움 표시한 메일까지 지우는 일반 `EXPUNGE`는 쓰지 않는다) ③ 본문으로 읽는 `text/plain`·`text/html` 부분은 앞 1 MiB만 읽는다(본문은 어차피 10,000자로 잘리고, 30MB HTML을 jsoup에 통째로 넣으면 힙 384MB에서 앱이 내려갈 수 있다) ④ `privacy.md`에 `RATE_LIMITED` 격리가 보낸 사람을 남긴다고 적는다 ⑤ 계정 상태는 서비스 트랜잭션 밖에서 읽는다(포털 세션 필터와 같은 틈, 0절 6의 글을 이것으로 바꾼다) ⑥ 메일 하나의 로그에 만든 문의 ID·댓글 ID를 남겨도 된다(개인정보 아님, S12-AC-34 글을 고친다) ⑦ 남는 위험(고치지 않음, `premortem.md` 7절): 서버가 내주지 못하는 메일 하나가 회차를 막음(시도 수를 세기 전 실패, `MAIL_IN_STALLED`로 드러남), COPY 길이 중간에 끊기면 사본 둘이나 지움 표시가 남음, 회차 스레드가 `RuntimeException`·`StackOverflowError` 밖의 `Error`로 멈추면 경보가 없음, 저장소가 5분 넘게 멈추면 첨부 메일이 격리됨 |

## 1. 표 (V25)

```sql
-- Owned by helpdesk (mail-in). One received email (MAILIN-6): its key is the SHA-256 of its Message-ID,
-- or of its raw bytes without one. A discarded email keeps no sender.
CREATE TABLE mail_received (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    message_key text NOT NULL,
    key_source text NOT NULL,
    sender_account_id bigint REFERENCES account (id),
    sender_address text,
    attempts integer NOT NULL,
    outcome text,
    reason text,
    issue_id uuid REFERENCES issue (id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    comment_id bigint REFERENCES comment (id) ON DELETE SET NULL,
    first_seen_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT mail_received_message_key_key UNIQUE (message_key),
    CONSTRAINT mail_received_key_source_check CHECK (key_source IN ('MESSAGE_ID', 'CONTENT')),
    CONSTRAINT mail_received_outcome_check CHECK (outcome IN ('ISSUE', 'COMMENT', 'NOTICE', 'DISCARDED', 'QUARANTINED', 'RATE_LIMITED')),
    CONSTRAINT mail_received_decided_check CHECK ((outcome IS NULL) = (decided_at IS NULL)),
    CONSTRAINT mail_received_attempts_check CHECK (attempts >= 0)
);
CREATE INDEX mail_received_first_seen_at_idx ON mail_received (first_seen_at);
CREATE INDEX mail_received_sender_account_id_idx ON mail_received (sender_account_id);

-- Owned by notification (outbox). The Message-ID of a sent email about an issue (MAIL-7), so that a
-- reply finds its issue (MAILIN-2).
CREATE TABLE mail_sent (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    message_id text NOT NULL,
    issue_id uuid NOT NULL,
    sent_at timestamptz NOT NULL,
    CONSTRAINT mail_sent_message_id_key UNIQUE (message_id)
);
CREATE INDEX mail_sent_sent_at_idx ON mail_sent (sent_at);
CREATE INDEX mail_sent_issue_id_idx ON mail_sent (issue_id);
```

- `issue.id`가 `uuid`다 [소스: V3 `outbox.issue_id uuid`, C-4 보고]. `mail_sent`는 `notification` 소유라 외래 키를 두지 않는다(V3 머리말 "notification 표는 외래 키 없이 계정·문의 ID를 든다" [소스: C-4 보고]). `mail_received.issue_id`의 `ON DELETE SET NULL`은 영구 삭제가 문의 행을 지우지 않아 돌지 않지만 외래 키는 그대로 둔다(같은 `helpdesk` 소유).
- 미리 거르는 첨부 규칙에 빈 파일(0바이트, `FILE_EMPTY` [소스: `AttachmentService` 653-654줄])을 넣는다. 까닭 문장은 "빈 파일" / "empty file". 문의의 공개 첨부 합계는 있는 `AttachmentRepository.publicBytes`를 쓴다.
- `Transactions.run`은 잠금 오류에 한 번 다시 하므로 훅이 두 번 돌 수 있다(첫 번은 되돌아간다).
- 받은 메일 기록에 `Message-ID` 글을 두지 않고 해시만 둔다. 길이가 정해지고, 로그나 DB를 보는 사람에게 주소 꼴의 값이 덜 드러난다. `mail_sent.message_id`는 우리가 지은 난수라 그대로 둔다.
- `start.TimeTest`(시간대 없는 시각 칸 금지)와 `TenantIdRule`이 이 표도 본다.
- `make-sample.py`는 두 표를 SQL로 채운다(IMAP 없이 만들 수 없다, 그 스크립트의 머리말이 허용 [소스: `make-sample.py` 6-7줄]). 빈 표가 있으면 실패한다 [소스: 385-391줄].

## 2. 계약 (C-4)

| 자리 | 바뀜 |
|---|---|
| `GET /api/console/system/status`(있음) | `mailIn: {enabled, offReason: NOT_SET\|SANDBOX\|null, lastSuccessAt, roundFailures, lastFailure: {kind: CONNECT|AUTH|FOLDER|UNSUPPORTED, at}\|AUTH\|FOLDER, at} \| null, retrying, quarantined90d}` |
| 알림 종류(콘솔 알림함, 있음) | `MAIL_IN_STALLED`, `MAIL_IN_QUARANTINED` |
| 정리 작업 목록(시스템 상태 `cleanupJobs`, 있음) | `mail-record-cleanup` |
| 보내는 메일(계약 파일 밖, `configuration.md`) | `Message-ID`, `Auto-Submitted`, `Reply-To` |
| 설정(`configuration.md`) | 켜지는 조건에 `MAIL_INBOUND_ADDRESS`, 샌드박스에서 꺼짐, `IMAP_PORT` 기본 993 |

- 새 HTTP 경로는 없다. `BoundaryList`에 더할 줄이 없다.
- 계약을 합친 뒤 한 번 돌 것: `ops.SystemStatusTest`(칸 목록, 테스트 탭이 맞춤), `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `project.NoStoreTest`, `boundary.BoundaryListTest`.
- 맞출 잠긴 테스트(C-4 탭이 `grep`으로 다 찾아 보고에 적는다): 시스템 상태 칸 목록, 정리 작업 이름 목록 셋(`ops/CleanupStall`, `issue/IdempotencyKeyTest`, `auth/PasswordResetCleanupTest`), 알림 종류 목록(서버와 웹 거울), 보내는 메일의 머리글을 정확히 보는 테스트가 있다면 그것, 웹의 C-18 견본(`C18SystemStatus.*.test.tsx`의 응답 견본에 `mailIn`), 콘솔 기본 가짜 서버(`console.ts`)의 상태 응답, `@popdesk/api` 계약 검증기 견본, `deploy/UpgradeHarnessTest`의 새 표.
- 웹: C-18에 구역 하나(`C18Parts.tsx`), C-7 문장 둘(`C7Inbox.tsx`의 `switch`와 `hrefOf`, `@popdesk/i18n`의 `inbox.*`). 포털은 바뀌지 않는다.

## 3. 시험

- IMAP 대역은 이미 테스트 의존성에 있는 GreenMail 2.1.14다(IMAP 지원 [소스: jar의 `greenmail/imap/`]). `support/MailServer`가 지금은 SMTP만 켠다 [소스: `MailServer.java:40`]. 메일 받기 테스트는 같은 GreenMail에 IMAP을 켜거나 따로 하나를 띄운다(C-5 탭이 정함. 다른 테스트의 받은 메일과 섞이지 않게 받는 계정을 따로 둔다). 메일은 GreenMail API로 받는 폴더에 넣는다(`GreenMailUser.deliver` 꼴 [추론]). 처리됨·격리 폴더의 메일은 IMAP으로 읽어 본다.
- GreenMail은 메일 받기 전용으로 따로 하나 둔다. 공용 것은 `ConnectedAppTest`가 테스트마다 `purgeEmailFromAllMailboxes`로 모든 메일함을 비운다 [소스: `ConnectedAppTest.java:37-40`, `MailServer.java:105-111`]. GreenMail의 CAPABILITY에 `MOVE`, `UIDPLUS`가 있고 IMAP STARTTLS는 없다 [소스: 안정성 검토가 jar에서 읽음]. 그래서 TLS 길(S12-AC-02)은 단위 테스트로 본다. 실패 종류는 같은 문맥에서 만든다: GreenMail을 멈추면 `CONNECT`, 비밀번호를 바꾸면 `AUTH`, 받는 폴더를 지우면 `FOLDER`(받는 폴더를 따로 만든 이름으로 둔다).
- 메일 받기를 켠 앱 문맥은 따로 하나다(`IMAP_*`, `MAIL_INBOUND_ADDRESS`, `IMAP_ALLOW_PLAINTEXT=true`, SMTP도 GreenMail로). 문맥 캐시 3을 넘지 않게 다른 테스트 클래스와 같은 바탕 클래스를 쓴다 [소스: `main-ops.md` 힙 절].
- 회차는 공용 `TestClock`을 1분 옮겨 부르고, 시스템 상태의 `mailIn.lastSuccessAt`이 옮긴 시각 뒤가 될 때까지 기다린다(고정 대기 금지, `check-test-style.py`). 한 번 옮기면 회차는 한 번 돈다. 다섯 번 실패(S12-AC-09)는 옮기고 기다리기를 여섯 번 한다. 1분을 옮기면 요청 수의 60초 창이 비므로 요청 수 시험(S12-AC-28)은 메일을 다 넣은 뒤 한 번만 옮긴다 [소스: `AccountRateLimit.java:22`]. 기록을 HTTP로 볼 길이 없으므로 결과는 HTTP로 본다(문의 목록, 댓글, 받은 안내 메일, 시스템 상태의 수). 메일함 쪽은 GreenMail로 본다. 기록 표 자체는 스키마처럼 DB를 봐야 하는 것만 DB로 본다(process C-5의 예외).
- `Message-ID`가 없는 메일은 `saveChanges` 없이 원문 바이트로 넣는다(Jakarta Mail이 지어 붙이지 않게).
- 답장 시험: 앱이 보낸 알림 메일을 GreenMail에서 읽어 그 `Message-ID`를 `In-Reply-To`에 넣은 메일을 만든다.
- 앱·DB 오류(S12-AC-09)는 HTTP로 만들기 어렵다. 저장소 오류는 `disk` 저장소의 폴더 권한으로 만들 수 있을지 C-5 탭이 본다 [추론]. 안 되면 C-7과 단위 테스트로 본다.
- 숨긴 E2E는 `popdesk-holdout`에 HTTP와 IMAP으로 쓴다. 대역은 `greenmail/standalone:2.1.14` 컨테이너(이미 deploy 테스트가 쓰는 이미지 [소스: `smtps.compose.yml:9`])에 IMAP을 켠다.
- 순서: 계약 → 테스트(서버, 웹) → 점검 → 구현(서버, 웹) → 검증. 11과 같다.
- 순수 계산 일곱의 단위 테스트는 구현을 합친 뒤 쓴다(process C-5).
