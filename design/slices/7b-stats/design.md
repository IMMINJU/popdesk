# 7b 묶음 설계

> 수용 기준은 같은 폴더 `acceptance.md`, 사고와 막는 것은 `premortem.md`, 화면은 `screens.md`다.
> 지금 코드에 대한 문장은 2026-10-15 `37a96a4`에서 읽은 것이다. 7a가 합쳐진 뒤 계약 단계에서 다시 읽는다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-15).

## 1. 표

새 표와 칸은 없다. 통계는 `issue`를 고객사와 상태로 묶어 센다. 운영 경보의 상태는 있는 경보 기록을 쓴다 [소스: `notification/OpsAlerts.java`, `notification/ops/OpsAlertService.java`].

## 2. 경로

| 하는 일 | 초안 | 답 |
|---|---|---|
| 통계 | `GET /api/console/stats` | 200 `CompanyStats{items: CompanyStat[]}` |

- `CompanyStat`: `companyCode`, `companyName`, `companyStatus`, `total`, `open`, `inProgress`, `resolved`, `closed`.
- `Notification.kind`와 `OpsAlerts.Kind`에 `CLEANUP_STALLED`를 더한다. 계약의 `Notification.issue` 설명("문의가 없는 종류" 목록)과 `OpsAlerts`의 주석("세 `_STALLED` 종류")도 고친다.

## 3. 통계 읽기

```
select c.code, c.name, c.status,
       count(i.id) as total,
       count(i.id) filter (where i.status in ('RECEIVED','CONFIRMED')) as open,
       … in_progress, resolved, closed
from company c
left join issue i on i.company_id = c.id and i.deleted_at is null
where (관리자) or c.id in (select company_id from company_agent where account_id = ?)
group by c.id
order by open desc, c.code
```

- 보기 범위는 고객사로 건다(배정). 문의 쪽 조건은 휴지통뿐이다(관리자와 배정된 상담원은 그 고객사의 문의를 모두 본다, RULE-03). `IssueScope.SQL`을 쓰지 않는 까닭은 고객사가 문의 없이도 나와야 하기 때문이다(`left join`). 구현 탭이 같은 조건인지 `IssueScope`와 견줘 본다.
- 색인: 새로 더하지 않는다. `issue(company_id, created_at)`이 이미 있다(V2). 구현 탭이 재서 150 ms를 넘으면 멈추고 알린다(그때 메인이 마이그레이션을 정한다).

## 4. 정리 작업 멈춤 경보

- `OpsAlertService.review()`에 정리 작업 검사를 더한다. `review`는 `Housekeeping`의 한 스레드에서, 같은 회차의 작업 다음에, 인스턴스 잠금을 쥔 동안만 돈다 [소스: `Housekeeping.round()`].
- 작업 목록은 `CleanupJob` 빈들(`name()`, `interval()`)이고, 마지막 성공 시각은 `CleanupRecords.lastRun(name)`이다. `CleanupJobs.list()`는 쓰지 않는다(주기가 없고, 빈이 없어진 옛 기록 행도 돌려준다).
- 기록이 없는 작업은 이 인스턴스가 잠금을 처음 쥔 회차의 시각(메모리)부터 잰다. 앱이 뜬 시각은 어디에도 남지 않는다.
- `지금 - 마지막 > 주기 + 24시간`인 작업이 하나라도 있으면 `raise(CLEANUP_STALLED)`, 없으면 `resolve`.

## 5. 웹

- 새 화면 C-5(`/stats`, 상담 쪽), C-7 새 문장 하나. `screens.md`다.
- 수를 누르면 C-3의 있는 필터 주소로 간다: `issuesHref({companyCode, status})`(매개변수 `companyCode`, 상태가 여럿이면 `status`를 되풀이) [소스: `issueFilters.ts`]. 열림은 상태 둘이다.

## 6. 순수 계산 클래스

| 클래스 | 메서드 | 받는 값 |
|---|---|---|
| 작업 멈춤 판정(서버) | 마지막 성공 시각(없으면 잠금을 처음 쥔 시각), 주기, 지금 → 멈췄는지 | 마지막 없음, 주기 + 24시간 경계(직전, 같음, 직후), 주기 10분과 하루 |

## 7. 일하는 차례

1. 계약(C-4): 경로 하나, 알림 종류 하나.
2. 테스트(C-5): 서버 탭 하나(통계, 경보), 웹 탭 하나(C-5, C-7, 메뉴). 숨긴 E2E는 7a의 것에 단계를 더하지 않고 새로 하나(관리자와 상담원이 보는 통계가 배정대로이고 휴지통의 문의가 빠진다).
3. 실패 확인, 점검, 잠금. 4. 구현(서버, 웹). 5. 검증.
