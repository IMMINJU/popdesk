# 8a 묶음 설계

> 수용 기준은 같은 폴더 `acceptance.md`, 사고와 막는 것은 `premortem.md`, 화면은 `screens.md`다.
> 지금 코드에 대한 문장은 2026-10-16 `e4fce44`에서 읽은 것이다(조사 메모 `C:\Users\minju\.claude\popdesk\notes\s8-survey.md`, crosscheck 검토가 줄 번호를 다시 봄). [소스]는 그 파일을 읽고 적었다는 표시다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-16).

## 0. 메인이 정한 것

`acceptance.md` 5절의 열여섯이다. 아래는 그것을 코드에 옮길 때 정한 것이다.

| # | 정한 것 | 까닭 |
|---|---|---|
| 1 | 목표 하나의 시계는 세 값이다: 앞서 센 업무 시간(초, `spent`), 지금 도는 구간의 시작(`since`, 멈췄으면 `null`), 확정(`MET`·`MISSED`·`NONE`(목표 없이 멈춤)·`null`). `since`와 확정이 모두 `null`이면 휴지통에서 잠깐 멈춘 것이다. 기한과 경고 시각은 이 셋과 정책으로 계산해 함께 저장한다 | 다시 계산(SLA-4)이 "센 시간은 그대로, 남은 것을 지금부터"이므로 센 시간을 따로 들고 있어야 한다. 지금 `resolved_at`은 열린 상태로 가면 지워진다 [소스: `IssueRepository.updateStatus` 443줄]. 복구가 끝난 시계를 다시 켜지 않으려면 "잠깐 멈춤"과 "끝남"이 갈려야 한다 |
| 2 | 시계를 바꾸는 모든 쓰기는 같은 순수 계산(`SlaClocks`)을 거친다: 문의의 계산 버전이 지금보다 낮으면 먼저 버전 경계마다 옮겨 센 뒤 그 일을 한다 | 등록, 첫 응답, 상태, 우선순위, 지우기·복구, 다시 계산 작업이 같은 결과를 내야 S8A-AC-21의 "어떤 한 순서"가 선다 |
| 3 | 목록 필터와 정렬을 위해 돌고 있는 목표 가운데 이른 기한과 이른 경고 시각을 문의 칸 둘(`sla_due_at`, `sla_warn_at`)로 함께 저장한다. 판정은 `sla_due_at < now`면 넘김, 아니고 `sla_warn_at <= now`면 곧 넘김 | 요청마다 업무 시간을 계산하지 않고 SQL과 색인으로 거른다. 두 목표 가운데 나쁜 쪽(5절 12)과 같은 값이 나온다 |
| 4 | 시계를 바꾸는 쓰기는 문의 행을 `FOR NO KEY UPDATE`로 잠그고 SLA 칸을 읽은 뒤 바꾼다. 상태 바꾸기(`move`)는 지금 잠그지 않는 조건부 UPDATE라, SLA 대상 문의일 때 잠금을 더한다 | 계산에 앞 값(`spent`, `since`)이 들어 조건부 UPDATE 한 줄로 못 한다 [소스: `IssueService.move` 434줄]. 잠금 차례는 앞 묶음과 같다(문의 행 하나, 그 앞에 정책 행 8번) |
| 5 | 정책 버전 행의 기본 키가 버전 번호다. 바꾸기는 지금 버전 행을 `FOR UPDATE`로 잠그고, 보낸 `version`이 그 번호가 아니면 409, 맞으면 `version + 1`을 넣는다. 마이그레이션이 버전 1(시간대 `null`)을 넣는다 | 같은 때 둘이 바꾸면 하나는 409다. 읽기 요청이 표를 쓰지 않는다 |
| 6 | 다시 계산은 `CleanupJob` 빈 `sla-recalculate`(1분)다. 돌려주는 수는 다시 계산한 문의 수다. 회차 처음에 시간대를 견준다 | 앞 정리 작업(자동 종료)과 같은 틀이라 잠금, 기록, C-18, 멈춤 경보가 저절로 붙는다 [소스: `IssueAutoClose`, `Housekeeping`]. 멈춤 경보의 문턱은 1분 + 24시간으로 느슨하지만 받아들인다 |
| 7 | 남은 목표가 0 이하면 기한과 경고 시각은 `since`다(새 버전의 시작 시각이나 다시 켜진 시각) | 넘긴 순간은 옛 버전 구간 안이라 새 정책으로는 시각이 없다. 작업이 돈 시각을 쓰면 S8A-AC-20("언제 돌든 같다")이 깨진다. 남은 시간(음수)은 센 시간으로 맞게 나온다 |
| 8 | 시계를 쓰는 쪽은 지금 정책 버전 행을 `FOR KEY SHARE`로 읽는다. 정책 저장(5)의 `FOR UPDATE`와 막혀서, 저장은 그 쓰기가 끝난 뒤 시각을 읽고 `starts_at`을 정한다 | 쓰는 쪽이 버전 V를 읽은 뒤 V+1이 그보다 이른 `starts_at`으로 커밋되면, 멈춘 시계가 V로 남아 다시 계산되지 않는다(C-1~3 검토). 쓰기끼리는 서로 막지 않는다 |
| 9 | 다시 계산은 `version`과 `updated_at`을 올리지 않는다. 계약의 `Issue.version` 설명에 그 예외를 적는다 | 작업이 버전을 올리면 그 사이 고치던 상담원이 409를 받는다 [소스: `IssueService.edit` 510줄대] |
| 10 | SLA 테스트는 따로 띄운 앱(별도 컨텍스트, `@TestPropertySource`)에서 돈다 | 정책은 설치에 하나다. 같은 앱을 쓰는 다른 클래스가 정책을 켜면 S8A-AC-01·11을 볼 수 없고, 다른 클래스의 문의가 SLA 대상이 된다(앞 꼴: `due/KiritimatiZoneTest`) |

## 1. 표 (V18)

```
create table sla_policy (
    version integer primary key,
    tenant_id bigint not null,
    time_zone text,                            -- 버전 1만 첫 회차 전까지 null
    starts_at timestamptz not null,            -- 이 버전으로 세기 시작한 시각
    changed_by bigint references account(id), -- null이면 시스템(설치, 시간대)
    changed_at timestamptz not null
);
create table sla_target (
    version integer not null references sla_policy(version),
    tenant_id bigint not null,
    priority text not null check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    first_response_minutes integer check (first_response_minutes between 1 and 525600),
    resolution_minutes integer check (resolution_minutes between 1 and 525600),
    primary key (version, priority)
);
create table sla_hours (
    version integer not null references sla_policy(version),
    tenant_id bigint not null,
    weekday smallint not null check (weekday between 1 and 7),   -- ISO, 1은 월요일
    opens time not null,
    closes time not null check (closes > opens),                  -- '24:00'은 자정까지
    primary key (version, weekday)
);
create table sla_holiday (
    version integer not null references sla_policy(version),
    tenant_id bigint not null,
    day date not null,
    primary key (version, day)
);
insert into sla_policy values (1, 1, null, now(), null, now());

alter table issue add column sla_version integer;     -- null이면 SLA 대상 아님
alter table issue add column fr_spent bigint;          -- 초
alter table issue add column fr_since timestamptz;
alter table issue add column fr_warn_at timestamptz;
alter table issue add column fr_due_at timestamptz;
alter table issue add column fr_outcome text check (fr_outcome in ('MET', 'MISSED', 'NONE'));
alter table issue add column res_spent bigint;
alter table issue add column res_since timestamptz;
alter table issue add column res_warn_at timestamptz;
alter table issue add column res_due_at timestamptz;
alter table issue add column res_outcome text check (res_outcome in ('MET', 'MISSED', 'NONE'));
alter table issue add column sla_warn_at timestamptz;  -- 돌고 있는 목표의 이른 경고
alter table issue add column sla_due_at timestamptz;   -- 돌고 있는 목표의 이른 기한
create index issue_sla_due_idx on issue (sla_due_at) where deleted_at is null and sla_due_at is not null;
create index issue_sla_warn_idx on issue (sla_warn_at) where deleted_at is null and sla_warn_at is not null;
create index issue_sla_running_idx on issue (sla_version)
    where deleted_at is null and (fr_since is not null or res_since is not null);
```

- 버전마다 목표·업무 시간·쉬는 날을 통째로 적는다(바꾸지 않는 행). 업무 시간이 없는 요일은 행이 없다. 일곱 요일 모두 행이 없으면 24시간이다. `time` 칸은 `24:00:00`을 받는다.
- 있는 문의는 모두 `sla_version`이 `null`이다(SLA-8).
- `make-sample.py`가 정책 버전 둘(목표, 업무 시간, 쉬는 날이 있는 것)과 대상 문의(돌고 있는 것, 확정한 것, 휴지통에서 멈춘 것)를 채운다. 빈 표가 있으면 견본 검사가 실패한다 [소스: `make-sample.py` 266줄].

## 2. 시계 계산

순수 계산 둘(7절):

- `BusinessCalendar`(한 버전의 시간대, 요일 구간, 쉬는 날): `seconds(from, to)`는 그 사이 업무 시간(초), `plus(from, seconds)`는 `from`에서 업무 시간으로 그만큼 지난 시각. 벽시계 구간을 시각으로 바꿔 더하고, 쉬는 날이 없는 온전한 주는 한 주의 업무 시간으로 건너뛴다. 서머타임으로 없는 시각이나 겹치는 시각은 `ZonedDateTime`의 기본 규칙(앞 오프셋)으로 바꾼다.
- `SlaClocks`: 한 문의의 두 시계에 대해
  - `catchUp(clock, versions)`: 문의의 계산 버전 다음의 각 버전 시작 시각까지 그 앞 버전으로 `spent`를 더하고 `since = max(since, 그 시작 시각)`으로 옮긴다(멈춘 시계는 그대로). 끝에 계산 버전이 지금 버전이 된다.
  - `stop(clock, at, target)`: 돌고 있지 않으면 아무것도 하지 않는다. 돌고 있으면 `at`까지 더하고 `since = null`, 확정은 목표가 없으면 `NONE`, `spent ≤ 목표`면 `MET`, 아니면 `MISSED`. 기한은 그대로 둔다.
  - `pause(clock, at)`: 돌고 있지 않으면 아무것도 하지 않는다. 돌고 있으면 `at`까지 더하고 `since = null`, 확정하지 않고 기한과 경고 시각을 지운다.
  - `resume(clock, at)`: 첫 응답 시계는 확정이 `null`일 때만, 해결 시계는 늘 `since = at`, 확정을 지운다. 복구는 확정이 `null`인 시계만 다시 켠다(휴지통에 들어갈 때 돌던 것).
  - `deadlines(clock, target, calendar)`: 돌고 있으면 `due = plus(since, 목표 - spent)`, `warn = plus(since, 목표 × 0.8 - spent)`(0 이하면 `since`, 0절 7), 목표가 없으면 `null`.
  - `state(clock, now)`, `remainingMinutes(clock, now, target, calendar)`: S8A-AC-18·19.

## 3. 시계를 바꾸는 곳

| 일 | 곳 [소스] | 시계 |
|---|---|---|
| 등록 | `IssueService.file` 330줄의 `issues.insert` 358줄(고객 등록과 대리 등록 모두) | 정책이 켜져 있으면 대상, 둘 다 `resume(올라온 시각)` |
| 첫 응답 | `CommentService` 132-133줄 → `updateFirstResponse` | 첫 응답 `stop`(`RESOLVED` 문의에 단 첫 댓글은 이미 멈춰 아무것도 하지 않음) |
| 해결·종료 | `IssueService.move` 434줄 → `updateStatus` | 열린 상태에서 갈 때 해결 `stop`, 첫 응답 `stop`(5절 4). `RESOLVED`→`CLOSED`는 아무것도 하지 않음 |
| 열린 상태로 | `IssueService.reopen` 409줄(`RECEIVED`로), `move`의 `RESOLVED`→`IN_PROGRESS` [소스: `IssueStatus` 24줄] | 해결 `resume` |
| 자동 종료 | `IssueRepository.updateClosedAutomatically` 619줄 | 바꿀 것 없음(`RESOLVED`에서 이미 멈춤) |
| 우선순위 | `IssueService.edit` 476-533줄(고객도) | 돌고 있는 시계의 기한을 새 목표로 |
| 지우기·복구 | `TrashService` 101줄, 134줄 | `pause` / 확정이 `null`인 시계만 `resume` |
| 다시 계산 | 새 `SlaRecalculation`(`CleanupJob`) | `catchUp` 뒤 기한 |
| 고객사 정지·보관, 계정 정지, 담당 비우기, 연결, 작업 | [소스: `CompanyService` 253-270줄 등, 상태를 바꾸지 않음] | 바꿀 것 없음(5절 15) |

모든 줄에서: 정책 행을 `FOR KEY SHARE`로 읽고(0절 8), 문의 행을 잠그고, `catchUp`, 그 일, `deadlines`, `sla_warn_at`·`sla_due_at`을 다시 정하고, 한 UPDATE로 쓴다. 다시 계산 작업만 잠그지 않은 행을 고르고(`skip locked`) `where sla_version = 읽은 버전`으로 쓴다.

## 4. 다시 계산 작업

- 회차 처음: 버전 1의 시간대가 `null`이면 `InstallationZone`으로 채운다(버전을 올리지 않음). 아니고 지금 버전의 시간대가 다르면 새 버전(지금 + 1, 바꾼 사람 `null`)을 정책 저장과 같은 잠금으로 넣는다.
- 대상: `sla_version < 지금 버전 and (fr_since is not null or res_since is not null) and deleted_at is null`(`issue_sla_running_idx`), `id` 차례, 500건, `for no key update skip locked`. 한 회차에 이 묶음을 대상이 없거나 10묶음이 될 때까지 되풀이한다(묶음마다 트랜잭션 하나). 2만 건은 4회차, 4분쯤이다(S8A-AC-41의 10분 안). `Housekeeping`은 스레드 하나라 한 회차가 길면 다른 작업이 기다리므로 묶음 수로 끊는다(시간으로 끊으면 테스트 시계가 멈춰 있어 한도가 걸리지 않는다).
- 다시 계산한 수를 기록에 남긴다(`cleanup_run`).

## 5. 경로와 계약

| 경로 | 누가 | 하는 일 |
|---|---|---|
| `GET /api/console/sla-policy` | 관리자 | `SlaPolicy` |
| `PUT /api/console/sla-policy` | 관리자 | 본문 `SlaPolicyChange`(`version`, `targets`, `hours`, `holidays`), 답 `SlaPolicy` |
| `GET /api/console/issues` | 상담 쪽 | 매개변수 `sla`(`warning`·`breached`), `sort`에 `slaDue` |

- `SlaPolicy`: `version`, `targets`(우선순위 넷, `LOW`부터: `priority`, `firstResponseMinutes`, `resolutionMinutes`), `hours`(요일 일곱, 월요일부터: `weekday`, `opens`, `closes`, `"HH:MM"`, 업무 시간이 없으면 둘 다 `null`, `closes`는 `"24:00"`까지), `holidays`(날짜, 오름차순), `timeZone`(버전 1의 첫 회차 전에는 앱의 시간대), `changedAt`, `changedBy`(`{id, name}`, 시스템이면 `null`).
- 콘솔 `Issue`와 `IssueSummary`에 `sla`(`IssueSla | null`): `firstResponse`, `resolution`(`SlaGoal`: `state`, `dueAt`, `remainingMinutes`). 확정 `NONE`은 `state: null`이다.
- 감사: 동작 `SLA_POLICY_CHANGED`, 대상 꼴 `{type: "SLA_POLICY", version}`(새 버전). `changes`는 `targets`, `hours`, `holidays` 가운데 바뀐 것의 앞뒤(각각 위 계약의 배열 꼴).
- 오류: 있는 `VERSION_CONFLICT`의 설명에 정책을 더한다(`conventions.md` 151줄). 새 오류 code와 칸 code는 없다.
- 계약 `Issue.version` 설명에 "다시 계산은 올리지 않는다"를 더한다(0절 9).
- 포털 계약은 바뀌지 않는다.

400의 칸(S8A-AC-04). 칸 이름은 배열 자리를 붙인다(`targets[2].resolutionMinutes`. 앞 꼴이 없어 이 묶음이 처음 정한다):

| 경우 | 칸 | code |
|---|---|---|
| 목표가 1~525,600 밖 | `targets[i].firstResponseMinutes` 등 | `OUT_OF_RANGE` |
| 해결 목표가 첫 응답 목표보다 짧음 | `targets[i].resolutionMinutes` | `OUT_OF_RANGE` |
| 우선순위가 넷이 아니거나 겹침 | `targets` | `UNKNOWN_VALUE` |
| 요일이 일곱이 아니거나 겹침 | `hours` | `UNKNOWN_VALUE` |
| 시작·끝 한쪽만 있음 | `hours[i].opens` 또는 `closes`(빈 쪽) | `REQUIRED` |
| 시각 꼴이 틀림(분 단위 `HH:MM`, 끝만 `24:00`) | `hours[i].opens` 등 | `INVALID_FORMAT` |
| 끝이 시작과 같거나 이름 | `hours[i].closes` | `OUT_OF_RANGE` |
| 쉬는 날이 500개를 넘음 | `holidays` | `TOO_MANY` |
| 같은 날짜가 둘 | `holidays[i]`(뒤의 것) | `UNKNOWN_VALUE` |
| `version` 없음 | `version` | `REQUIRED` |

## 6. 웹

- 새 화면 C-13(`/sla`, 관리자), C-3 칸·필터·정렬, C-4 구역, C-12 감사 낱말. `screens.md`다.
- 남은 시간 글자는 `@popdesk/app`에 순수 함수 하나(`slaWords`)로 둔다(분 → "3시간 20분 남음", 음수 → "1일 2시간 넘김").
- 감사 바뀐 값의 글(목표·업무 시간·쉬는 날 배열의 다른 점)은 `auditWords.ts`에 순수 함수로 둔다.

## 7. 순수 계산 클래스 (C-10의 단위 테스트와 변이 테스트)

| 클래스 | 메서드 | 받는 값 |
|---|---|---|
| `BusinessCalendar`(서버) | `seconds`, `plus` | 업무 시간 안·밖·경계에서 시작, 쉬는 날, 24시간, 일부 요일만, 끝 `24:00`, 서머타임 시작·끝 날(`America/New_York`), 한 요일 1시간에 목표 525,600분 |
| `SlaClocks`(서버) | `catchUp`, `stop`, `pause`, `resume`, `deadlines`, `state`, `remainingMinutes` | 버전 셋 건너뛰기, 버전 시작보다 늦게 켜진 시계, 멈춘 시계에 `stop`·`pause`, 목표 없이 `stop`, 남은 목표 0 이하, 80% 경계와 기한 경계(직전·같음·직후), 확정 뒤 재오픈, 휴지통 뒤 복구 |
| `slaWords`(웹) | 분 → 글 | 0, 59, 60, 1440, 음수 |
| 감사 바뀐 값 글(웹) | 앞뒤 배열 → 글 | 목표 하나 바뀜, 요일이 쉬는 날로, 쉬는 날 더하고 뺌 |

## 8. 계약 앞에 볼 것 (맞출 잠긴 테스트와 문서)

- `IssueSummary`에 칸이 늘면 목록 응답 견본을 고정한 잠긴 테스트(계약 검증기 견본, `console.ts` 기본 가짜 서버)를 C-5 탭이 맞춘다(`process.md` C-4 가드레일).
- 관리자 옆 메뉴 배열을 그대로 견주는 잠긴 테스트(`layout.inbox.test.tsx` 54줄, `adminOnly.1b.test.tsx` 14줄).
- 목록 매개변수 허용 목록(정의 `ConsoleIssueController` 54줄, 검사 177줄)과 `UNKNOWN_PARAMETER` 테스트.
- 정리 작업 이름을 나열한 테스트 도움: `ops/CleanupStall.java` 59줄 `JOBS`, `issue/IdempotencyKeyTest.java` 56줄 `EARLIER_JOBS`, `auth/PasswordResetCleanupTest.java` 37줄 `EARLIER_JOBS`(새 작업의 기록 수가 2건을 넘으면 잘못 집힌다, 84-88줄).
- 감사 종류를 그대로 견주는 `web/console/src/app/auditWords.words.test.ts` 40-75줄과 내보내기의 종류 나열.
- 문서: `docs/operate/configuration.md` 283줄(정리 작업 목록에 `sla-recalculate`), 15줄(`POPDESK_TIMEZONE` 설명에 SLA 업무 시간과 바꾸면 버전이 오름, 롤링 배포 주의).

## 9. 일하는 차례

1. 계약(C-4): 경로 둘, 문의 칸, 매개변수 둘, 감사 종류는 서버 탭. 웹 계약 탭 하나: `TextField`의 `type`에 `'time' | 'number'`를 더하고 story 껍데기(공용 컴포넌트 API라 전체 경로).
2. 테스트(C-5): 서버 탭 둘(정책·감사·보이기 / 시계·다시 계산·경쟁, 따로 띄운 앱에서, 0절 10), 웹 탭 하나(C-13, C-3, C-4, C-12), 숨긴 E2E 하나(정책을 켜고 문의를 올려 업무 시간 밖·안에서 기한, 첫 응답 확정, 목표를 늘려 다시 계산, 고객에게 보이지 않음). 서머타임(S8A-AC-12)은 따로 띄운 앱에 `America/New_York`을 주고 테스트 시계(`support/TestClock`)를 2026-11-01로 옮겨 본다. 경쟁(S8A-AC-21)은 `AutoCloseRaceTest`처럼 DB 잠금을 쥐고 본다. S8A-AC-42와 경계는 단위 테스트(C-10)다.
3. 실패 확인, 점검, 잠금. 4. 구현(서버, 웹). 5. 검증(순수 계산 단위 테스트와 변이 테스트, 2만 건 다시 계산과 목록 측정).
