# 10b 묶음 설계

> `acceptance.md`의 기준을 어떻게 만드는지 적는다. 조사는 메인 세션의 서브에이전트가 했다(2026-10-18). [소스]는 지금 코드에서 읽은 것, [추론]은 읽지 않고 짐작한 것이다. 10a가 합쳐진 뒤의 코드를 기준으로 C-4 전에 다시 본다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-18).

## 0. 메인이 정한 것

| # | 정한 것 |
|---|---|
| 1 | 10a의 `helpdesk.privacy` 패키지에 둔다(`acceptance.md` 5절 19, 10a 뒤 다시 봄): `CompanyExportService`(작업 만들기, 목록, 파일 열기), `CompanyExportWriter`(스냅샷에서 읽어 ZIP을 씀), `CompanyExportRepository`, 순수 계산 `ZipEntryNames`. 자동 정리는 `helpdesk.trash`에 `IssueRetention`(정리 작업), `RetentionSchedule`(순수 계산), `IssueRetentionSettings`(설정 읽기와 시작 검사)를 둔다 |
| 2 | 작업 실행: 작업 하나를 위한 단일 스레드 실행기를 `CompanyExportService`가 쥔다(`SmartLifecycle`로 멈출 때 끝냄). 앱 안의 잠금(`AtomicBoolean`)으로 한 번에 하나(409). `POST`가 `InstanceLock.held()`를 보고 아니면 503을 낸다. 실행기는 작업을 시작하기 전에 한 번 더 본다(`Housekeeping`은 스레드를 늘 띄우고 회차마다 `held()`를 본다 [소스: `Housekeeping.java:91-93`]). 다른 일반 실행기는 없다 [소스] |
| 3 | 스냅샷: `CompanyExportWriter`는 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서(10a 계정 내보내기의 꼴 [소스: `AccountExportService.java:66-69`, 읽기 전용 `REPEATABLE READ` `TransactionTemplate`]. 감사 내보내기는 기본 격리에 한 쿼리다) 계정, 문의, 댓글, 작업, 첨부 행을 `fetchSize`를 준 커서로 흘려 읽으며 JSON을 ZIP에 쓰고 첨부 행 목록(ID, 이름, 저장소 자리)을 모은다. 트랜잭션을 닫은 뒤 첨부 바이트를 `AttachmentService`에 행을 받는 공개 메서드를 더해 읽어 붙인다(`AttachmentStorages.read`는 `AttachmentService`만 부른다 [소스: javadoc]). 그 사이 지워진 파일은 빠진 목록으로 간다. `manifest.json`은 맨 끝에 쓴다 |
| 4 | 직렬화는 포털 DTO를 다시 쓰지 않고 내보내기 전용 record(`ExportIssue`, `ExportComment`, `ExportTask`, `ExportCustomer`, `ExportManifest`)로 한다. 칸을 바꿀 때 포털 응답이 따라 바뀌지 않게 하려는 것이다. 칸 목록은 `acceptance.md` 2.2절이고 잠긴 칸 스냅샷 테스트가 지킨다 |
| 5 | 파일 자리: `POPDESK_EXPORT_DIR`(기본 `/tmp/popdesk-exports`). 쓰는 동안 `{작업 ID}.zip.part`, 다 되면 이름을 바꾼다. 시작할 때 `.part`를 모두 지운다 |
| 6 | 뜰 때 한 번, 마이그레이션 뒤 웹 서버가 열리기 전(`InitializingBean` 또는 웹 서버보다 앞 단계의 `SmartLifecycle`. 단일 실행 잠금은 Flyway보다 먼저 잡혀 이때 이미 쥐고 있다, `acceptance.md` 5절 15): `RUNNING` → `FAILED`(`INTERRUPTED`), 파일 자리에서 `DONE` 작업의 파일이 아닌 것(`.part`, 이름을 바꾼 뒤 `DONE`을 쓰기 전에 죽은 `.zip`) 지우기, 파일 없는 `DONE` → `EXPIRED`. 그 전에 온 `POST`는 503 |
| 7 | 정리 작업 둘(`CleanupJob`): `company-export-expiry`(10분, 24시간 지난 `DONE`의 파일을 지우고 `EXPIRED`), `issue-retention`(1시간 간격으로 돌고, 상태 표의 `last_moved_at`에서 24시간이 지났을 때만 옮긴다). 잠긴 `PasswordResetCleanupTest`, `IdempotencyKeyTest`는 목록에 없는 정리 작업을 새것으로 본다. 함께 쓰는 테스트 컨텍스트에서 새 작업 둘은 늘 0을 남겨야 한다(자동 정리 꺼짐, 작업 없음) [소스: `CleanupJob`, `Housekeeping`] |
| 8 | 자동 정리 옮기기는 잠그지 않는 쿼리로 대상 ID를 100개씩 고른 뒤, 문의 하나에 트랜잭션 하나로 `sla.begin()` → 그 문의 잠금 → 조건을 다시 본 `update` → 지우기 몸통(대기 메일·웹훅 버리기, 이력, SLA 멈춤)을 한다(대상 고르기는 영구 삭제 작업 `purgeDue`의 묶음 꼴 [소스: `TrashService.java:208-230`], 잠금 차례는 사람이 지울 때의 `delete`와 같게 `sla.begin()` 뒤 문의 잠금 [소스: `TrashService.java:111-112`]. `purgeDue`는 `sla.begin()`을 부르지 않는다). `TrashService`에 사람 확인과 감사를 뺀 `deleteBySystem`을 두고 `deleted_by`는 `NULL`(이미 널 가능 [소스: V17, 영구 삭제가 이미 널을 넘김]) |
| 9 | 시작 검사: `IssueRetentionSettings`가 `InitializingBean`에서 던진다(프로세스가 끝남) [소스: `StorageStartCheck`, `configuration.md:26`이 이미 "멈춘다"]. 감사 보관 키(`AuditRetention`)는 앱을 띄운 채 요청을 막는 꼴인데, 문서와 규칙이 이 키는 "뜨지 않는다"라 다르게 둔다 |
| 10 | 알림 종류 셋(`COMPANY_EXPORT_READY`, `COMPANY_EXPORT_FAILED`, `ISSUE_RETENTION_ARMED`)은 `AdminInbox`(인터페이스, `notification/AdminInbox.java`)와 구현 `notification/ops/AdminInboxService.java` 둘에 메서드를 더하고 `NotificationKind`에 더한다. `notification.kind`에는 CHECK가 없다 [소스: V8 주석]. 포털 쪽 종류에는 더하지 않는다 |
| 11 | 웹: C-9에 구역 하나와 대화상자 하나(C-9 안, 공용 컴포넌트 API 바꾸지 않음), C-7 문장 셋, C-11 "자동 정리" 표시 |

## 1. 표 (V23)

```sql
-- 고객사 내보내기 작업(PII-8).
CREATE TABLE company_export (
    id uuid PRIMARY KEY,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL REFERENCES company (id),
    requested_by bigint NOT NULL REFERENCES account (id),
    include_internal boolean NOT NULL,
    include_private boolean NOT NULL,
    state text NOT NULL CHECK (state IN ('RUNNING', 'DONE', 'FAILED', 'EXPIRED')),
    failure text,
    size_bytes bigint,
    requested_at timestamptz NOT NULL,
    finished_at timestamptz
);
-- 한 번에 하나(5절 3)를 DB에서도 막는다.
CREATE UNIQUE INDEX company_export_running_key ON company_export (tenant_id) WHERE state = 'RUNNING';

-- 문의 자동 정리의 지난 상태(PII-6). 행 하나.
CREATE TABLE issue_retention (
    id smallint PRIMARY KEY CHECK (id = 1),
    tenant_id bigint NOT NULL,
    period_seconds bigint,         -- 지난번 본 설정의 길이, 꺼져 있으면 NULL
    moves_from timestamptz,        -- 이 시각부터 옮긴다(첫날 기다림)
    last_moved_at timestamptz      -- 지난번 옮긴 때(하루 한 번)
);
INSERT INTO issue_retention (id, tenant_id) VALUES (1, 1);

CREATE INDEX issue_closed_for_retention ON issue (closed_at) WHERE status = 'CLOSED' AND deleted_at IS NULL;

-- 휴지통에서 마지막으로 복구한 때(5절 12). 자동 정리는 greatest(closed_at, restored_at)로 센다.
ALTER TABLE issue ADD COLUMN restored_at timestamptz;

-- 자동 정리 첫날 알림의 수와 일수(5절 14).
ALTER TABLE notification ADD COLUMN retention_count integer, ADD COLUMN retention_days integer;
```

- `issue.deleted_by`는 이미 널 가능이다 [소스: `V17` 8줄]. 휴지통 목록은 left join이라 널이어도 줄이 빠지지 않는다 [소스: `IssueRepository.java:585`]. `purgeDue`가 널을 넘기는 곳은 첨부의 `deleted_by`다(앞 판의 근거를 고침).
- 기간을 일수로 바꾸는 법과 받는 꼴(일·시간만, 해·달은 거절)은 `AuditRetention`과 같게 한다 [소스: `AuditRetention.java:10-14`].

## 2. 계약 (C-4)

| 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|
| `POST /api/console/companies/{code}/exports` | `{includeInternal, includePrivate}` | 202 `CompanyExport` | 400(값 없음), 401, 403, 404, 409 `EXPORT_RUNNING`, 503 `SERVER_BUSY`(잠금을 잃은 동안, `Retry-After: 5`. `conventions.md` 181줄의 `SERVER_BUSY` 설명에 이 경우를 더한다) |
| `GET /api/console/companies/{code}/exports` | – | 200 `CompanyExport[]`(최신순, 최근 50) | 401, 403, 404 |
| `GET`·`HEAD /api/console/company-exports/{id}/file` | – | 200 `application/zip` | 401, 403, 404 |

- `CompanyExport`: `id`, `state`, `failure`(`INTERRUPTED`, `STORAGE`, `DISK`, `UNEXPECTED` 가운데 하나 또는 `null`), `includeInternal`, `requestedBy`(`{id, name}`), `requestedAt`, `finishedAt`, `size`.
- 휴지통 응답(`TrashedIssue.deletedBy`)이 `null`일 수 있게 하고 설명에 "자동 정리"를 적는다.
- 알림 종류 셋과 그 칸(`companyCode`, 첫날 알림의 대상 수와 기간).
- 감사 종류 `COMPANY_EXPORTED`, `COMPANY_EXPORT_DOWNLOADED`. C-12 종류 묶음은 둘 다 "고객사"다. 10a의 잠긴 `C12AuditLog.10a.test.tsx` 71·74줄이 "내보내기" 묶음을 정확히 둘(`AUDIT_EXPORTED`, `ACCOUNT_EXPORTED`)로 단언하고, 7a·6b 테스트는 "고객사"를 `arrayContaining`으로 본다(앞 판의 판단이 거꾸로였다, 10a 뒤 다시 봄). C-5에서 잠긴 `__tests__/console1b.ts`의 `KIND_GROUPS`를 테스트 쪽이 맞춘다.
- 설정 키 `POPDESK_EXPORT_DIR`을 `SettingKeys`에 더한다(없으면 시작할 때 모르는 키 경고). `deploy/compose.yml`이 `POPDESK_ISSUE_RETENTION`과 `POPDESK_EXPORT_DIR`을 넘긴다.
- 웹 테스트 쪽이 앱의 기본 가짜 서버(`console.ts`)에 `GET /companies/{code}/exports`의 빈 목록을 더한다(빠지면 C-9 앞 테스트가 오류 띠로 깨진다, 6a의 라벨과 같은 꼴).
- 정리 작업 목록을 가진 잠긴 테스트가 셋이다: `PasswordResetCleanupTest`, `IdempotencyKeyTest`, `ops/CleanupStall.java:59-61`의 `JOBS`(`CleanupStalledTest`와 `sla/SlaRunStepsTest`가 씀, 목록에 없는 작업은 기다리지 않는다). C-5에서 셋 모두 영향을 본다. `configuration.md`의 정리 작업 목록과 `upgrade.md`에 두 작업을 더한다.
- 내려받기 `GET`의 감사 쓰기는 익명화가 사슬 머리를 쥔 동안 기다린다(10만 건에서 수십 초) [소스: `AnonymizationService.java:95`의 `rechain.finish`]. 자동 정리는 감사를 남기지 않아 `AuditRechain`과 겹치지 않는다.
- 계약 뒤 한 번 돌 것: `project.NoStoreTest`, `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `boundary.BoundaryListTest`, `boundary.AdminOnlyContractTest`, `PortalNotificationContractTest`.

## 3. 테스트 쓰는 법 (C-5)

- 내보내기는 설치에 하나씩 돌아 다른 테스트와 섞인다. 내보내기 테스트는 새 기반 클래스 하나의 컨텍스트에서 돌고, 작업이 `RUNNING`이 아닐 때까지 목록을 다시 읽어 기다린다(고정 대기 없음).
- ZIP은 테스트가 받아 풀어 본다. 칸 목록은 JSON의 키 집합으로 단언한다.
- 자동 정리는 따로 띄운 컨텍스트(`POPDESK_ISSUE_RETENTION=P90D`, 고정 시계)에서 `TestClock.advance`로 91일과 24시간을 넘긴다. 정리 작업이 돈 것은 정리 기록으로 기다린다. 시작 거절(`P30D`, 틀린 꼴)은 deploy 태그 테스트로 이미지가 끝나는지 본다.
- 다시 뜰 때 `RUNNING` → `FAILED`는 따로 띄운 컨텍스트에서 표에 `RUNNING` 행을 SQL로 넣고 앱을 띄워 본다(스키마처럼 DB를 봐야 하는 예외).
- 업그레이드: 이 키를 읽는 코드가 처음 생긴다. 틀리거나 짧은 값을 이미 넣은 설치는 업그레이드 뒤 뜨지 않으므로 릴리스 노트에 적는다.

## 4. 웹

- 내보내기 구역은 C-9 안의 따로 둔 컴포넌트(`CompanyExports`, `CompanyLabels`와 같은 꼴: 자기 `useLoad`와 대화상자). 5초 다시 읽기가 화면 전체를 다시 읽지 않게 한다. 자리는 라벨 구역 뒤(맨 끝).
- 다시 읽기는 `@popdesk/app`의 `useUnreadCount` 꼴을 따른다: 탭이 가려지면 쉬고, 늦은 답을 버리고, 401이면 멈추고, 고정 `setInterval`이다 [소스: `unread.ts:36-38`]. 코드는 `console/src/app`에 둔다.
- 상태 배지는 `Badge`(공용 `StatusBadge`의 종류를 늘리지 않는다), 끝남·실패를 알리는 숨긴 `role="status"` 한 줄, 선택 상자 설명은 `Checkbox`의 `description`으로 늘 보임, "내보내기" 단추는 구역 머리에 하나, 크기는 `formatBytes`.
- 알림 문장은 지금 꼴("고객사 {code} …", 마침표 없음 [소스: `ko.ts:150`])을 따르고 `en.ts`에도 넣는다.
- C-11 `C11Trash.tsx`의 `deletedBy.name`이 널을 받게 고친다. 문의 이력은 지금처럼 "시스템이"로 둔다.
- 테스트는 `layout.inbox.test.tsx`의 가짜 시계 꼴(`useFakeClock`, `advanceTimersByTime`)로 다시 읽기를 본다.
