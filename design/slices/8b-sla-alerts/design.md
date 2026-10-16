# 8b 묶음 설계

> 수용 기준은 같은 폴더 `acceptance.md`, 사고와 막는 것은 `premortem.md`, 화면은 `screens.md`다.
> 지금 코드에 대한 문장은 2026-10-17 `7440937`에서 읽은 것이다. 조사 메모와 C-1~3 검토 둘(서버, 기준·화면)은 메인 세션의 scratchpad에 두었고(`8b-facts.md`, `8b-review-server.md`, `8b-review-web.md`), 검토가 줄 번호를 다시 봤다. [소스]는 그 파일을 읽고 적었다는 표시다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-17).

## 0. 메인이 정한 것

`acceptance.md` 5절의 열다섯이다. 아래는 그것을 코드에 옮길 때 정한 것이다.

| # | 정한 것 | 까닭 |
|---|---|---|
| 1 | 알렸음 표시 둘과 첫 응답 확정 시각은 `IssueClocks`에 더해 시계 칸과 같은 쓰기로 쓴다. 사람의 쓰기는 `IssueClocks.SET`·`INSERT`·`COLUMNS`를 거치고, 작업의 `updateClocks`는 칸을 손으로 나열하므로 같은 칸을 거기에도 더한다. `SlaClocks.Clock`은 바꾸지 않는다 | 사람의 쓰기는 `IssueRepository` 376줄(`INSERT`)과 387줄(`SET`), 읽기는 89·531줄과 `SlaIssueRepository` 38줄(`COLUMNS`)이다. 새 칸은 `IssueClocks.params()`와 `read()`에도 더한다. 작업 쪽은 `SlaIssueRepository` 58-85줄이 칸과 `unnest` 배열을 손으로 적는다[소스]. 같은 한 UPDATE로 써야 8a 0절 8b를 지킨다. `Clock`은 잠긴 `SlaClocksTest`가 125번 만드는 record라 칸을 더하면 컴파일이 깨진다. `IssueClocks`를 쓰는 테스트는 없다 |
| 2 | 다음 알림 시각은 칸으로 저장하지 않고 식 색인으로 둔다(1절). 목표마다 "시계가 멈췄으면 없음, 표시가 `null`이면 경고 시각, `WARNING`이면 기한, `BREACHED`면 없음" 가운데 이른 것(`least`는 null을 건너뛴다). 알림 단계는 같은 식으로 `<= now`와 `order by`를 쓴다 | 저장한 파생 칸은 쓰기마다 다시 정해야 하고, 하나라도 빠지면 어긋난 행이 맨 앞을 막는다(서버 검토 4). 식이면 어긋날 일이 없다. 멈춘 시계도 경고·기한 시각을 남기므로 `since is null` 갈래가 있어야 한다[소스: `SlaClocks.stop` 111줄] |
| 3 | 넘김 판정은 8a대로 `now > dueAt`이다. 기한과 같은 순간에 읽힌 행은 보낼 것이 없고 다음 회차에 다시 읽힌다. 그 행은 식 값이 `now`라 차례의 맨 뒤라 다른 알림을 막지 않는다 | 8a의 판정을 바꾸지 않는다 |
| 4 | 알림 단계는 단계를 시작할 때 `clock.instant()`를 다시 읽는다. 묶음은 100건, 한 트랜잭션, `for no key update of i skip locked`. 문의마다 표시를 `where id = ? and sla_version = ?`로 먼저 쓰고 1행일 때만 알림함·메일·웹훅을 넣는다. 묶음이 100건보다 적거나 한 회차 10묶음이면 멈춘다 | 회차 시각은 회차 처음에 한 번 읽어 앞 작업만큼 낡는다(서버 검토 5). 연결마다 `lock_timeout` 5초라 500건을 쥐고 있으면 사람의 쓰기가 5초를 넘겨 기다릴 수 있다(서버 검토 10, 자동 종료도 100건이다). 표시와 알림이 한 트랜잭션이라 "한 번만"이 커밋 하나로 선다 |
| 5 | 받는 사람은 묶음마다 한 번 읽는다: `ACTIVE` 관리자 목록, 묶음에 든 고객사마다 `ACTIVE` 배정 상담원, 담당자의 상태. 담당자가 볼 수 있는지는 관리자면 늘 보고, 상담원이면 그 고객사 배정으로 본다(`ASSIGNEE_UNAVAILABLE`와 같은 뜻) | 알림 하나마다 `findById`와 `visible`을 부르면 넘김 500건에 쿼리가 만 개를 넘는다(서버 검토 10). 판정은 helpdesk의 `IssueNotifications`에 새 메서드로 둔다(누구에게 보낼지는 한 곳이 정한다[소스: `IssueNotifications` 25-58줄]). 고객사의 `ACTIVE` 상담원을 주는 메서드는 지금 없다(`listAssignable`은 관리자가 섞이고, `listMembers`는 상태를 거르지 않고 쪽을 나누고 읽는 사람의 범위로 묶인다) |
| 6 | 알림 단계는 정책 행을 잠그지 않는다 | 시계를 바꾸지 않고 표시만 쓴다. 정책 저장이 그 사이 버전을 올려도 읽은 버전 조건으로 쓰고, 다시 계산이 새 버전으로 옮긴다 |
| 7 | 시계를 쓰는 모든 일(`SlaWrite`의 `filed`, `answered`, `moved`, `reprioritized`, `deleted`, `restored`, 작업의 다시 계산)은 `SlaAlerts`로 표시와 첫 응답 확정 시각을 정한다. 쓰기가 받은 시계의 버전이 지금보다 낮으면(`caughtUp`이 옮김) 그 일을 하기 전에 따라잡기만 한 시계와 앞 시계를 견주어 다시 계산과 똑같이 표시를 올리고 요약 행에 더한다 | 5절 5. 앞 시계를 받는 다섯(`filed` 빼고)은 먼저 `caughtUp`을 부른다[소스: `SlaWrite` 45·57·72·78·85줄]. `filed`는 새 시계라 따라잡을 것이 없다 |
| 8 | 요약 행에 더하기는 `insert … on conflict (tenant_id) where closed_at is null do update set became_breached = sla_recalc_report.became_breached + excluded.became_breached, …` 한 문장이다. 사람의 쓰기는 정책 `FOR KEY SHARE` → 문의 행 → 요약 행 차례로 잡는다. 작업의 묶음은 문의 → 요약 행이다 | 순환이 없다. 수가 모두 0이면 문장을 보내지 않는다 |
| 9 | 요약 닫기는 회차의 다시 계산이 끝난 뒤 따로 트랜잭션에서: 정책 지금 버전 행 `FOR KEY SHARE` → 열린 요약 행 `for update` → 옛 버전의 돌고 있는 문의 수를 다시 셈 → 0이면 `closed_at`을 쓰고 세 수 가운데 0이 아닌 것이 있으면 `ACTIVE` 관리자 전부에게 `SLA_RECALCULATED`(세 수를 알림 행 칸에 적음) | 정책 저장(`FOR UPDATE`)이 닫기 뒤로 줄을 서서 "끝났다"를 본 뒤 새 버전이 끼지 않는다(서버 검토 13). 그래도 닫은 바로 뒤에 다시 계산이 시작되면 그것은 다음 요약이다 |
| 10 | 다시 계산 작업의 `updateClocks`는 `returning id`로 쓴 문의를 돌려준다. 조건에서 빠진 문의는 세지 않는다 | 지금은 합계 `int` 하나라 어느 문의가 빠졌는지 모른다[소스: `SlaIssueRepository` 54-86줄] |
| 11 | 회차의 두 단계는 서로 막지 않는다: 알림 단계를 `try`로 감싸 실패를 기억하고 다시 계산을 하고, 끝에 실패가 있었으면 예외를 낸다(회차가 기록되지 않고 1분 뒤 다시) | S8B-AC-28. 같은 행에서 늘 실패하면 하루 뒤 `CLEANUP_STALLED`가 된다(사전 부검 3절) |
| 12 | 항목은 있는 `IssueInbox.add`로 넣고 `actorId`는 `Viewer.SYSTEM_ACCOUNT_ID`(0)다. 요약은 `AdminInbox`에 새 메서드 | 자동 종료와 같다. 화면 문장은 "누가"를 쓰지 않는다(`screens.md` 1절) |
| 13 | 새 종류: `IssueInbox.Kind`에 넷, `MailKind`에 둘(`SLA_RESPONSE_BREACHED`, `SLA_RESOLUTION_BREACHED`), `WebhookKind`에 같은 둘, 콘솔 `NotificationKind`에 다섯. 포털 허용 목록은 그대로 | 저장 이름이 콘솔 enum에 없으면 읽기가 실패한다[소스: `ConsoleNotificationController` 83-91줄]. `SLA_WARNING`이라는 이름은 쓰지 않는다: 잠긴 C-7·P-7 테스트가 그 이름을 "모르는 종류"로 쓴다 |
| 14 | 첫 응답 확정 시각 `fr_settled_at`은 확정이 `null`에서 `MET`·`MISSED`가 되는 쓰기의 시각이다. 응답으로 확정될 때는 `first_response_at`과 같은 값(`CommentService.add`의 `now`, 마이크로초로 자른 것. `SlaClocks.truncate`의 초 단위가 아님)을 쓴다. `NONE`은 확정 시각을 두지 않는다. 한 번 정하면 바뀌지 않는다 | 통계가 `first_response_at = fr_settled_at`으로 "응답으로 확정"을 가른다(5절 12). 시계 시각은 `SlaClocks.truncate`로 자르지만 `first_response_at`은 자르지 않는다[소스: 서버 검토 1] |
| 15 | 통계 SLA 칸은 있는 고객사 쿼리에 `filter` 집계를 더한다. 첫 응답: `count(*) filter (where i.fr_outcome = 'MET' and i.fr_settled_at >= :since)`, 중앙값 `percentile_disc(0.5) within group (order by i.fr_spent) filter (where i.fr_outcome in ('MET','MISSED') and i.fr_settled_at >= :since and i.first_response_at = i.fr_settled_at)`. 해결은 `coalesce(i.resolved_at, i.closed_at) >= :since`와 `res_outcome`. 정책이 꺼져 있으면 쿼리를 바꾸지 않고 `sla`를 `null`로 준다. 새 색인은 두지 않는다 | STATS-3(문의 표를 바로 센다). 한 번에 읽는 쿼리라 확정 시각 색인을 타지 않는다(서버 검토 15). 200 ms 참고 값을 넘으면 그때 SLA 칸을 따로 쿼리로 뗀다 |

## 1. 표 (V20)

```
alter table issue
    add column fr_alerted text check (fr_alerted in ('WARNING', 'BREACHED')),
    add column res_alerted text check (res_alerted in ('WARNING', 'BREACHED')),
    add column fr_settled_at timestamptz;

create index issue_sla_alert_idx on issue ((least(
        case when fr_since is null then null
             when fr_alerted is null then fr_warn_at
             when fr_alerted = 'WARNING' then fr_due_at end,
        case when res_since is null then null
             when res_alerted is null then res_warn_at
             when res_alerted = 'WARNING' then res_due_at end)))
    where deleted_at is null and sla_version is not null;

create table sla_recalc_report (
    id bigserial primary key,
    tenant_id bigint not null,
    opened_at timestamptz not null,
    closed_at timestamptz,
    became_breached integer not null default 0 check (became_breached >= 0),
    became_warning integer not null default 0 check (became_warning >= 0),
    released integer not null default 0 check (released >= 0)
);
create unique index sla_recalc_report_open_idx on sla_recalc_report (tenant_id) where closed_at is null;

alter table notification
    add column sla_became_breached integer,
    add column sla_became_warning integer,
    add column sla_released integer;
```

- 알림 표의 세 칸은 FK 없이 수만 둔다(V3 머리 주석 "notification은 helpdesk 표에 기대지 않는다"). 요약 표는 helpdesk(sla)의 누적용이다. 닫힌 행은 지우지 않는다.
- 채우기(이미 있는 8a 문의. 0.8.0은 8a와 8b를 함께 내므로 실제 설치에는 없고 개발 설치만 해당한다): `fr_settled_at`은 `fr_outcome in ('MET','MISSED')`인 문의에 `coalesce(first_response_at, resolved_at, closed_at)`. 응답 없이 닫힌 뒤 재오픈해 응답이 달린 문의는 이 식이 늦은 시각을 고르지만 개발 설치뿐이라 받아들인다. 표시는 모두 `null`이라 첫 회차가 이미 지난 것을 알린다.
- 칸 이름은 C-4 계약 탭이 바꿀 수 있다(바꾸면 이 절을 고친다). 새 표의 `tenant_id`는 D-18대로 거르지 않는다. 시각 칸은 `timestamptz`(`TimeTest`). 식 색인의 `least`·`case`·비교는 immutable이다.

## 2. 회차의 차례 (`SlaRecalculation.run`)

1. 시간대 견주기(8a 그대로, 따로 트랜잭션)
2. 알림 묶음(0절 2-5, 실패는 기억하고 이어 감)
3. 다시 계산 묶음(8a) + 앞뒤 견주기, 표시 올리기, 요약 행 더하기(0절 7, 8, 10)
4. 옛 버전의 돌고 있는 문의가 0이면 요약 닫기(0절 9)
5. 돌려주는 수는 3에서 다시 계산한 문의 수. 2에서 실패했으면 예외

## 3. 순수 계산 `SlaAlerts` (C-8 목록, C-10 PIT)

`helpdesk/sla`에 둔다. 앱과 DB 없이 돈다. 공개 메서드(이름은 구현 탭이 정하되 주석에 받는 값의 범위와 돌려주는 값을 적는다):

| 하는 일 | 받는 것 | 주는 것 |
|---|---|---|
| 단계 | 시계 하나, `now` | `STOPPED`(멈춤·확정·휴지통), `NO_GOAL`(돌지만 목표 없음), `OK`, `WARNING`, `BREACHED`. `SlaClocks.state`는 멈춤과 목표 없음을 둘 다 `null`로 주므로 `Clock.running()`을 함께 본다 |
| 알림 정하기 | 시계 하나, 표시, `now` | 보낼 것(`null`, `WARNING`, `BREACHED`)과 새 표시. 넘김이면 경고를 건너뛴다 |
| 표시 낮추기 | 시계 하나, 표시, `now` | 단계가 표시보다 앞이면 단계로 낮춘 표시(`OK`·`NO_GOAL`이면 `null`). `STOPPED`면 그대로 |
| 다시 계산 견주기 | 앞 시계 둘, 뒤 시계 둘, 표시 둘, `now` | 문의 단위 바뀜(`BECAME_BREACHED`, `BECAME_WARNING`, `RELEASED`, `null`)과 새 표시 둘. 바뀜이 앞의 둘이면 뒤로 간 목표의 표시를 올리고, 아니면 올리지 않는다. 앞으로 간 목표는 낮춘다 |
| 첫 응답 확정 시각 | 앞 확정, 뒤 확정, 앞 확정 시각, 이 일의 시각 | 새 확정 시각 |

단위 테스트에 넣을 경우(테스트 쪽이 주석만 보고 쓰므로 주석에 적는다): 첫 응답 `ok→breached`와 해결 `breached→ok`가 함께(문의 단계 그대로, 표시 올리지 않음), 해결이 이미 `BREACHED`로 알려진 문의의 첫 응답 `ok→warning`, 목표가 없어진 `BREACHED` 표시, 휴지통 시계의 표시, 기한과 같은 순간.

## 4. 계약 (C-4)

| 곳 | 바뀜 |
|---|---|
| 콘솔 `Notification.kind` | `SLA_RESPONSE_WARNING`, `SLA_RESPONSE_BREACHED`, `SLA_RESOLUTION_WARNING`, `SLA_RESOLUTION_BREACHED`, `SLA_RECALCULATED` 더함. `issue` 설명의 "문의 없는 종류" 목록에 `SLA_RECALCULATED` 더함 |
| 콘솔 `Notification.slaSummary` | 새 칸(필수, `null` 가능) `SlaSummary {becameBreached, becameWarning, released}`(정수 ≥ 0, 모두 필수) |
| `GET /api/console/stats` | 쿼리 `days`(정수, 7·30·90, 기본 30). 400 설명 |
| `CompanyStat.sla` | 새 칸(필수, `null` 가능) `CompanySlaStat {firstResponse, resolution}`, 각각 `SlaGoalStat {medianMinutes(정수, null 가능), met, missed}` |
| 포털 | 바뀌지 않는다 |

## 5. C-4·C-5에서 맞출 잠긴 테스트

C-4 탭 보고(`tasks/s8b-c4-server-report.md` 4절)로 고쳤다. 모두 잠겨 있다. 웹훅 스냅숏은 종류마다 줄을 요구하는 테스트가 없어 깨지지 않는다(새 줄은 S8B-AC-18의 테스트가 더한다).

| 테스트 | 왜 |
|---|---|
| `notify/SandboxMailTest`(59-61, 164-182줄) | `MailKind` 전부와 종류마다 시나리오를 요구한다(소스를 글로 읽어 C-9에서 깨진다). 새 메일 종류 둘의 시나리오에는 SLA 정책이 켜져야 한다. 그 앱 컨텍스트를 같이 쓰는 클래스를 grep으로 보고, 정책을 켠 채 끝나지 않게 한다(테스트 쪽이 정하고 C-7이 본다) |
| `inbox/InboxSteps.CONSOLE_ITEM_FIELDS`(40-41줄) | 콘솔 항목 칸 집합에 `slaSummary`. 이것으로 `InboxReadTest` 156줄과 `TaskInboxTest` 195-196줄이 지난다 |
| `stats/StatsSteps.FIELDS`(22-23줄) | 항목에 `sla`. `CompanyStatsTest`는 고치지 않는다(최상위 칸은 그대로) |
| `web/packages/api/src/testing/contract.test.ts` 견본 셋(`item()` 419-431, `stat()` 441-450, CLEANUP_STALLED 466-477) | `slaSummary: null`, `sla: null` |
| `web/console/src/__tests__/console4.ts` 51-64 `notification()` | `slaSummary: null`. 타입 오류이자 계약 검사로 `C7Inbox.6b`·`6c`·`7a`·`7b` 테스트가 실행에서 깨진다 |
| `web/console/src/__tests__/console7b.ts` 37-40 `stat()` | `sla: null`. 고치기 전에는 `statsResponse`의 계약 검사로 C5Stats 테스트 전부가 깨진다 |
| `web/console/src/__tests__/storyFetch7b.ts` 12-15 `stat()` | `sla: null`(`Screens7b.overflow.stories.tsx`가 씀) |
| C-4 뒤 한 번 돌릴 것 | `project.NoStoreTest`, `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `boundary.BoundaryListTest`(process.md C-4), `start.TimeTest`, `start.TenantIdTest`, `db.tables()`를 쓰는 클래스(`grep -rln "db.tables()" server/src/test`) |
| `sla-recalculate`를 쓰는 8a 테스트 | `SlaRecalculationTest`, `SlaRaceTest`, `SlaPriorityTest`, `SlaUntouchedInstallTest`, `deploy/SlaTimeZoneTest`. 작업 이름과 수의 뜻을 바꾸지 않으므로 그대로 지나야 한다. C-5 탭이 한 번 돈다 |

정리 작업 목록 셋(`CleanupStall.JOBS`, `EARLIER_JOBS` 둘)은 새 작업이 없어 바꾸지 않는다.

## 6. 견본 (`make-sample.py`)

새 표 `sla_recalc_report`에 닫힌 행 하나와 `SLA_RECALCULATED` 항목, 표시가 채워진 문의가 있어야 한다. 차례:

1. 8a가 만든 SLA 문의 뒤에, 돌고 있는 SLA 문의 하나를 두고 짧은 목표(1분, 24시간 업무)로 경고·넘김 알림을 기다린다(3분 안).
2. 다른 돌고 있는 문의 하나가 넘김이 되도록 목표를 줄이는 정책 바꿈을 하고 요약 항목을 기다린다(요약은 수가 0이 아닐 때만 생긴다).
3. 이 둘을 있는 "보내기 대기가 빌 때까지" 기다림 앞에 둔다(덤프에 `queued` 행이 남지 않게).

서버 구현 탭은 견본을 바꾸면 deploy 견본 테스트 하나를 이미지로 돌린다(process.md C-8).

## 7. 문서

- `docs/operate/configuration.md`의 정리 작업 줄 `sla-recalculate`에 "SLA 알림도 한다"를 더한다. 운영 문서에 MAIL-6 50통 한도와 넘김 메일의 관계를 한 줄.
- `docs/integrate/`의 웹훅 설명에 새 종류 둘.
- `design/spec/architecture.md` 잠금 표(140-165줄)에 세 줄: 다시 계산 묶음(문의 `skip locked` → 요약 행), 알림 묶음(문의 `skip locked`, 정책 행 없음), 요약 닫기(정책 `FOR KEY SHARE` → 요약 행 `for update`). 8a의 다시 계산 줄도 없으므로 함께 적는다.
- 릴리스 노트 재료는 C-11에서 `verify.md`에 모은다.
