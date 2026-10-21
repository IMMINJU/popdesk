# 7a 묶음 설계

> 수용 기준은 같은 폴더 `acceptance.md`, 사고와 막는 것은 `premortem.md`, 화면은 `screens.md`다.
> 지금 코드에 대한 문장은 2026-10-15 `ebb0bf5`에서 읽은 것이다(조사 메모 `~\.claude\popdesk\notes\s7-survey.md`). [소스]는 그 파일을 읽고 적었다는 표시다. 6c가 합쳐진 뒤 계약 단계에서 6c의 코드를 다시 읽는다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-15).

## 0. 메인이 정한 것

`acceptance.md` 5절의 열셋이다. 아래는 그것을 코드에 옮길 때 정한 것이다.

| # | 정한 것 | 까닭 |
|---|---|---|
| 1 | 지우기, 복구, 영구 삭제, 자동 종료는 모두 문의 행을 잠그거나 조건부 UPDATE로 한다. 지우기와 복구와 영구 삭제는 `FOR NO KEY UPDATE`로 잠근 뒤 조건(휴지통인지, 30일인지)을 다시 본다 | STATUS-7, TRASH-2·3이 정한 꼴이다. 앞 쓰기는 `lockByKey`가 범위로 잠그므로 지운 문의를 잡지 못하고 404가 된다 [소스: `IssueRepository.lockByKey` 138-142줄] |
| 2 | 상태 바꾸기의 조건부 UPDATE(`updateStatus`)에 `deleted_at is null`을 더한다 | `move()`는 잠그지 않고 `findByKey`로 읽은 뒤 조건부 UPDATE를 한다. 읽은 뒤 지워지면 지운 문의의 상태가 바뀐다 [소스: `IssueRepository.java` 362-387줄, `IssueService.move` 434-462줄] |
| 3 | 자동 종료 기준 시각은 새 칸 `auto_close_from`이다 | `resolved_at`은 해결 시각이라 복구로 미루면 그 뜻이 바뀐다(S7A-AC-22) |
| 4 | 영구 삭제는 문의 하나를 한 트랜잭션으로 한다. 첨부는 그 안에서 `pending`·`stored`를 모두 `purging`으로 바꾸고 `file_name`과 `comment_id`를 비운다. 커밋한 뒤 파일을 지우고 행을 지운다 | TRASH-3, ATT-10. `attachment.comment_id`가 `comment`를 가리키고(V13, deferred) 파일 지우기가 행의 저장 위치를 읽으므로(`attachments.filePlace`), 행은 커밋 뒤까지 남고 댓글을 가리키지 않아야 한다(C-1~3 검토에서 찾음) [소스: `AttachmentService.purgeOfComment` 474줄, `removeFiles` 495-509줄] |
| 5 | 정리 작업 둘은 `CleanupJob` 빈이다(`trash-purge` 1시간, `issue-auto-close` 10분) | 앞 정리 작업과 같은 틀이라 기록과 C-18이 저절로 붙는다 [소스: `notification/ops/Housekeeping.java`, `CleanupJobs.java`] |
| 6 | 시스템이 하는 일의 `Viewer`는 계정 0, 역할 `ADMIN`이다(`Viewer.system()` 같은 이름 하나를 둔다) | 상태 바꾸기의 알림(`statusChanged`)은 `actor.staff()`를 보고, 행을 읽는 데 범위가 든다. 지금 `Viewer`에는 시스템 꼴이 없다(C-1~3 검토에서 찾음) |
| 7 | 계약에서 `null`을 받는 enum 칸(`IssueHistoryActor.role` 등)은 `enum`에도 `null`을 넣는다. 구현 서버 탭이 `OpenApiConfig`에서 고치고 계약 파일을 다시 만든다(2026-10-16 메인이 정함) | `role`이 `type: ["string", "null"]`인데 `enum`에 `null`이 없어, popdesk 자신(계정 0)이 한 이력의 `role: null`을 웹 계약 검증기가 거부한다. 앞 묶음 견본에는 시스템 행위자가 없어 드러나지 않았다(C-5 웹 탭이 찾음) |

## 1. 표 (V17)

6c가 V16을 쓴다.

```
alter table issue add column deleted_at timestamptz;
alter table issue add column deleted_by bigint references account(id);
alter table issue add column purged_at timestamptz;
alter table issue add column auto_close_from timestamptz;
update issue set auto_close_from = resolved_at where status = 'RESOLVED';
create index issue_trash_idx on issue(deleted_at) where deleted_at is not null and purged_at is null;
create index issue_auto_close_idx on issue(auto_close_from) where status = 'RESOLVED' and deleted_at is null;
```

- 휴지통에 있음: `deleted_at is not null and purged_at is null`. 영구 삭제함: `purged_at is not null`(`deleted_at`도 남는다). 산 문의: `deleted_at is null`.
- 앞 설치의 `RESOLVED` 문의는 해결 시각을 기준으로 삼는다(업그레이드한 뒤 첫 10분에 7일 넘은 해결 문의가 닫힌다. `deploy/upgrade/README.md`와 릴리스 노트에 적는다).
- `deploy/upgrade/make-sample.py`가 S7A-AC-60의 행을 API로 만든다(영구 삭제는 휴지통 경로로).

## 2. 지운 문의를 빼는 곳

- `IssueScope.SQL`에 `i.deleted_at is null`을 더한다(지금 조건의 `(… or …)` 바깥에 `and`로). 범위를 거치는 읽기와 잠금이 모두 따른다 [소스: `IssueScope.java` 15-19줄, 조사 메모 1절]. 6c가 더하는 별칭을 받는 꼴도 같은 조건을 쓴다.
- 범위를 거치지 않는 곳(조사 메모 1절)을 하나씩 고친다:

| 곳 | 고칠 것 |
|---|---|
| `NotificationDirectory.find`, `WebhookSender`(문의가 있는지만 본다) | 웹훅은 보내기 직전에 산 문의인지 본다. 아니면 행을 지운다(S7A-AC-10) |
| `InboxDirectory.issues`(알림함 줄의 키와 제목) | 이미 `visible`로 거른 뒤라 그대로 둔다. 거른 뒤인지 계약 단계에서 확인한다 |
| `IssueRepository.updateAssigneesCleared`, `TaskRepository.updateAssigneesCleared` | 산 문의만(S7A-AC-04, 5절 6) |
| `LabelRepository`의 문의 수 둘 | `issue`를 붙여 산 문의만 |
| `AttachmentService.delete`(문의 행을 잠그지 않는다고 주석에 적혀 있다) | 그 트랜잭션에서 `lockByKey`로 문의를 잠근다. 아니면 지운 문의의 첨부가 지워진다(S7A-AC-12) |
| 6c의 연결 수와 사슬 검사 | `issue_link`만 읽고 `issue`를 범위로 붙이지 않는다(숨은 연결을 세야 한다, S7A-AC-05). 6c 구현 지시서에 넣었다 |
| 6c의 연결 알림(상태 바꾼 문의의 이어진 문의) | 산 문의만. 상대 담당자가 볼 수 있는지는 범위로 이미 걸린다 |

- 메일 보내기 직전 확인은 받는 사람이 문의를 볼 수 있는지 범위로 본다 [소스: `IssueVisibility.canSee` → `issues.visible`]. 범위에 휴지통이 들어가므로 따로 고치지 않는다. 테스트가 이를 본다(S7A-AC-10).

## 3. 경로

계약 탭(C-4)이 `conventions.md`의 꼴과 견줘 정한다. 메인의 초안:

| 하는 일 | 초안 | 답 |
|---|---|---|
| 지우기 | `DELETE /api/console/issues/{key}` (관리자) | 204 |
| 휴지통 목록 | `GET /api/console/trash?page&size` (관리자) | 200 `PagedTrashedIssue` |
| 복구 | `POST /api/console/trash/{key}/restore` (관리자) | 200 콘솔 `Issue` |
| 영구 삭제 | `DELETE /api/console/trash/{key}` (관리자) | 204 |

- `TrashedIssue`: `key`, `title`, `companyCode`, `companyName`, `status`, `deletedAt`, `deletedBy`(사람), `purgeAt`.
- 새 오류 code: `RESTORE_LINK_CONFLICT`(409, 몸에 걸린 상대 키 목록. 꼴은 계약 탭이 있는 409의 꼴과 견줘 정한다).
- 감사 종류 셋: `ISSUE_DELETED`, `ISSUE_RESTORED`, `ISSUE_PURGED`(`AuditAction`과 계약의 `action` enum).
- 이력 칸 `TRASH`: 값 `TRASHED`. 계약의 `IssueHistoryEntry.field` 설명에 더한다.
- 관리자 전용 경로라 `boundary.AdminOnlyContractTest`의 목록에 든다(테스트 쪽 일).

## 4. 쓰기의 차례와 잠금

| 쓰기 | 차례 |
|---|---|
| 지우기 | 관리자인지(403) → `lockByKey`(범위에 휴지통 조건이 있어 지운 문의는 404) → `deleted_at`, `deleted_by`, `version + 1`, `updated_at` → 그 문의의 보내기 대기 행 DELETE → 이력 `TRASH` → 감사 `ISSUE_DELETED` |
| 복구 | 관리자 → 키로 휴지통의 문의를 찾고 그 문의와 이어진 문의의 행을 ID 차례로 함께 잠금(휴지통 조건은 잠근 뒤 다시 봄, 없으면 404) → 연결 다시 보기(409) → `deleted_at`, `deleted_by`를 비움, `RESOLVED`면 `auto_close_from = now`, `version + 1` → 이력 → 감사 `ISSUE_RESTORED` |
| 영구 삭제(사람) | 관리자 → 키로 휴지통의 문의를 잠금(404) → 비우기(아래) → 감사 `ISSUE_PURGED` → 커밋 → 첨부 파일 지우기 |
| 영구 삭제(시스템) | 30일 지난 문의 ID를 고른다(잠그지 않음) → 문의마다 트랜잭션: ID로 잠금 → 아직 휴지통이고 30일이 지났는지 다시 봄(아니면 건너뜀) → 비우기 → 커밋 → 파일 |
| 자동 종료 | 한 묶음(100)씩: `select id from issue where status = 'RESOLVED' and auto_close_from <= now - 7일 and deleted_at is null order by id limit 100 for update skip locked` → 같은 조건을 다시 넣은 조건부 UPDATE(`where id = any(?) and status = 'RESOLVED' and auto_close_from <= … and deleted_at is null returning id`) → 돌아온 문의마다 같은 트랜잭션에서 이력(한 사람 0), 알림과 웹훅(5절). Postgres `UPDATE`에는 `LIMIT`이 없고, 여러 행을 힙 차례로 잠그면 잇기(ID 차례 두 행)나 담당 비우기와 교착할 수 있어 ID 차례로 잡고 잡힌 것은 건너뛴다 |

비우기(한 트랜잭션): 그 문의의 첨부(`pending`·`stored`)를 `purging`으로, `file_name = null`, `comment_id = null`(ID를 받아 둠) → 알림함 항목(그 문의의 것과 `linked_issue_id`가 그 문의인 것), 보내기 대기 행, 멘션, 작업 기록, 작업, 연결, 라벨 붙임, 댓글, `issue_idempotency` 행 DELETE → 이력 `TITLE`·`CONTENT`의 값 null → 문의 `title = ''`, `content = ''`, `search_text = ''`, `purged_at = now`, 이력 `TRASH`(전 `TRASHED`, 뒤 `PURGED`, 한 사람: 관리자 또는 0). 커밋한 뒤 첨부마다 파일을 지우고 행을 지운다. 파일을 못 지우면 행이 `purging`으로 남고, `AttachmentCleanup`이 마저 지운다. 그 작업은 문의가 영구 삭제된 첨부는 `purged`로 두지 않고 행을 지우게 고친다 [소스: `AttachmentCleanup.java` 71-81줄].

- `architecture.md` 잠금 순서 표에 다섯 줄을 C-10에서 더한다. 6c 설계 3절의 "연결을 바꾸는 쓰기는 잇기와 끊기뿐"에 영구 삭제(문의 하나를 잠그고 그 문의의 연결을 지운다)를 더한다. 영구 삭제는 휴지통의 문의만 하고, 휴지통의 문의에는 잇기가 닿지 않으므로(404) 검사와 겹치지 않는다.
- 상태를 `RESOLVED`로 바꾸는 `updateStatus`가 `auto_close_from = now`를 같이 쓰고, 다른 상태로 가면 지운다(`CLOSED`도 지운다).
- 6c의 잇기는 두 문의를 잠근다. 지우기와 잇기가 겹치면 잇기의 잠금이 범위 조건으로 404가 된다(지운 쪽이 먼저면) 또는 지우기가 기다린다.

## 5. 자동 종료의 알림

- 자동 종료는 상담 쪽 종료와 같은 `notifications.statusChanged`와 웹훅을 부른다. 한 사람은 시스템(0절 6)이라 "자기 자신"으로 빠지는 받는 사람이 없다. 6c의 연결 알림도 같은 자리에서 불린다.
- 고객 메일 `ISSUE_CLOSED`는 사람 이름을 넣지 않는다("The support team closed your issue") [소스: `MailText.closed`]. 바꾸지 않는다.
- 콘솔 알림 항목의 `actor`는 계정 0을 찾지 못해 `null`이다 [소스: `InboxDirectory.actors`]. 계약의 `Notification.actor` 설명("문의가 없는 항목이면 `null`")에 "시스템이 한 일도 `null`"을 더한다(C-4).

## 6. 웹

- 콘솔만 바뀐다. 포털은 바뀌지 않는다(지운 문의는 포털에서도 404이고, 포털 화면은 있는 404 화면을 쓴다).
- 새 화면 C-11(`/trash`, 관리자), C-4의 "문의 지우기", 이력 `TRASH` 문장, C-12 감사 종류 셋, C-18 정리 작업 이름 둘. 바뀌는 곳은 `screens.md`다.
- 공용 컴포넌트는 새로 만들지 않는다(`DataTable`, 확인 대화상자).

## 7. 순수 계산 클래스 (C-10의 단위 테스트와 변이 테스트)

| 클래스 | 메서드 | 받는 값 |
|---|---|---|
| 휴지통 시각(서버) | 지운 시각 → 영구 삭제 예정 시각, 지금이 영구 삭제할 때인지 | 30일 경계(직전, 같음, 직후), 시간대와 서머타임(UTC로 셈) |
| 복구 연결 검사(서버, 6c의 연결 규칙을 쓴다) | 문의의 연결들 → 어기는 연결 | 50개 경계, 사슬 셋, 문제없음 |
| 휴지통 문장(웹) | 영구 삭제 예정 시각 → "n일 뒤" 글 | 오늘, 1일, 30일, 지남 |

## 8. 계약 앞에 볼 것

- 앞 묶음의 모든 operation을 훑는 테스트 넷과 `AdminOnlyContractTest`를 계약 뒤 한 번 돈다.
- `IssueHistoryEntry.field`, 감사 종류, 정리 작업 이름이 는다. 그것을 늘어놓은 잠긴 테스트와 웹 견본을 테스트 탭이 맞춘다.
- 문서: `docs/use/permissions.md` 42·119·201줄 근처(휴지통), `docs/integrate/conventions.md` 45줄(404), 오류 code 표, 감사 종류 표, 이력 칸, 정리 작업 목록. `docs/operate/`에 정리 작업 둘과 업그레이드 뒤 첫 자동 종료(1절).

## 9. 일하는 차례

1. 계약(C-4): 서버 껍데기(경로 넷, 감사 종류 셋, 이력 칸, 오류 code), 계약 파일, 웹 클라이언트.
2. 테스트(C-5): 서버 탭 둘(지우기·404·목록 빼기·보내기 / 휴지통 목록·복구·영구 삭제·자동 종료·같은 때), 웹 탭 하나, 숨긴 E2E 하나(관리자가 지우고, 상담원·고객이 어디서도 못 보고, 대기 메일이 안 나가고, 복구하면 그대로이고, 다시 지운 뒤 앱 시계로 30일을 넘기면 DB에 글이 남지 않는다).
3. 실패 확인, 점검(C-7, 수용 기준 3절), 잠금.
4. 구현(C-9): 서버 탭 하나와 웹 탭 하나.
5. 검증(C-10): `local-ci.sh` 전체와 이미지·deploy, 숨긴 E2E, 변이 테스트(7절), 브라우저(C-11, C-4 지우기, 세 폭).
