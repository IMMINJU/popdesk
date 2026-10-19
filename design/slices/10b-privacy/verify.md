# 10b 묶음(고객사 내보내기와 문의 자동 정리) 검증 (C-10)

> 2026-10-19 메인이 이 PC에서 했다. GitHub Actions 10월분을 다 써서 CI는 돌지 않았다.
> C-10은 닿은 부분만 돈다(`process.md` C-10 첫 줄).
> 숫자는 각 실행의 로그(`C:\Users\minju\.claude\popdesk\tasks\s10b-ci\`)와 탭 보고에서 옮겼다.

## 1. 구현 탭 둘이 끝났고 맡은 테스트는 모두 통과한다

| 탭 | 맡은 것 | 결과 |
|---|---|---|
| `s10b-i-server` | `pending.txt` 서버 클래스, deploy `ExportRetentionSampleTest` | 94개 통과, 견본 테스트 통과. 같이 돈 ops·boundary 255, start 126, trash·notification·auth 350, issue 337, notify 140, privacy(10a) 73에서 실패 0. 계약과 생성 클라이언트 차이 없음 |
| `s10b-i-web` | `pending.txt` 웹 줄과 story | console 1,423을 포함한 패키지 테스트 전체, story 실행기 725, typecheck, lint, build, 포털 번들 검사 통과. dup 5.69% → 5.57%. 공용 컴포넌트 API는 그대로 |

- 잠긴 테스트가 틀린 것은 없었다. 서버 탭이 처음 실패한 둘(`IssueRetentionRestoreTest`, `IssueRetentionRaceTest`)은 모두 구현 탓이었다.
- 서버 탭이 자동 정리에서 겪은 NPE(잠금을 기다린 뒤 붙여 읽은 계정 칸이 빔)와 같은 꼴을 앞 묶음 코드에서 찾아 고쳤다(4절).
- 합친 뒤 main의 서버·배포 코드는 서버 탭이 돌린 트리와 같다(다른 것은 웹 테스트 잠금 파일). 그래서 닿은 서버 패키지는 탭 결과로 갈음했다.

## 2. 이 PC에서 돈 것 (닿은 부분만)

| 무엇 | 결과 | 시간 |
|---|---|---|
| 관문: `tests-lock.py check`, `check-fixture-literals.py` | 통과. 운영 코드에 걸린 견본 글자 0 | 수 초 |
| `popdesk:test` 이미지 빌드 | 통과 | |
| 숨긴 E2E `CompanyExportFlowTest` | 9 통과 | 1분 36초 |
| 순수 계산 단위 테스트 `ZipEntryNamesTest`, `RetentionScheduleTest` | 90 통과 | |

## 3. 변이 테스트

| 대상 | 만든 변이 | 잡음 | 살아남음 |
|---|---|---|---|
| 서버 PIT `ZipEntryNames` | 23 | 23(하나는 메모리 오류로 잡힘) | 0 |
| 서버 PIT `RetentionSchedule` | 17 | 17 | 0 |

- 단위 테스트는 테스트 쪽 서브에이전트가 conventions와 acceptance를 기준으로 썼다(`eb54bad`).
- 같은 ZIP 경로가 겹칠 때 `~2`, `~3`을 붙이는 규칙은 코드 주석에만 있고 기준 문서에 없다. 경로에 첨부 ID가 들어가 같은 첨부가 두 번 들어올 때만 생긴다.
- 웹에는 새 순수 함수가 없어 Stryker는 돌지 않았다.

## 4. C-10에서 찾아 고친 것

### 문의를 잠그며 붙여 읽은 계정 칸이 기다린 뒤 비었다

- 서버 탭이 자동 정리에서 겪은 NPE의 원인: PostgreSQL READ COMMITTED에서 `… from issue i left join account … for no key update of i`가 잠금을 기다리는 사이 `i`의 담당자·삭제자 칸이 바뀌면, 다시 확인할 때 잠그지 않은 `account` 행은 처음 읽은 것을 그대로 써서 이름·역할이 빈다. 매퍼가 역할을 읽다 NPE를 낸다.
- 메인이 서브에이전트로 잠금 문장 전체를 훑어 같은 꼴 둘을 찾았다: `IssueRepository.lockByKey`·`lockPair`(담당자), `lockTrashedByIds`(삭제자). 나머지(고객사, 소속 요청, 초대, 첨부, SLA)는 바뀌지 않는 키로 붙이거나 붙인 칸을 쓰지 않는다.
- 테스트 탭(`s10b-t-lockjoin`)이 경합 테스트 둘을 썼고 지금 코드에서 세 번 모두 500으로 재현됐다(`32b7899`). 담당자를 바꾸는 사이 댓글 달기, 복구하는 사이 연결 문의 지우기. 기준은 `acceptance.md` 5절 26.
- 구현 탭(`s10b-i-lockjoin`)이 세 메서드를 "문의 행 ID만 잠그는 문장 → 잠금 없이 다시 읽기"로 나눴다(`d3b7622`). 두 테스트 통과(3/3), issue와 구조 검사 342, trash·link·label·task 277 통과.

### 첨부 정리 경합 테스트 하나가 10b 앞부터 실패한다

- `AttachmentCleanupTest.cleanupRacesStepThree`가 main과 10b 서버 전(`d0ff091`)에서 모두 실패했다(구현 탭이 `attachment` 패키지를 돌다 찾음).
- 서브에이전트가 커밋별로 직접 쟀다. 단언 조건은 늘 참이고, 조건 한 번 확인에 3.4초가 걸려 `Wait.holds`(5초 동안, 최대 10초) 안에 세 번째 확인이 끝나지 않았다. `AttachmentRows.find`가 모든 표의 모든 칸마다 질의를 보내고, `DbInspector`가 질의마다 새 연결을 연다.
- 표가 늘수록 느려졌다: 6a 끝(표 28개) 2.5초 통과, V18(35개) 3.2초 아슬아슬, V20(36개)부터 늘 실패, 지금(38개) 3.4초. 운영 코드 회귀는 아니다. 6b·6c·8a `verify.md`가 같은 원인을 넘겨 왔고, 9·10a C-10은 `attachment`를 돌지 않아 드러나지 않았다.
- 테스트 쪽 탭(`s10b-tfix-dbinspector`)이 잠긴 도움 파일 둘만 고쳤다(`a0f4212`, 다시 잠금 `243efe4`). 테스트 본문과 `Wait`의 시간은 그대로다.
  - `AttachmentRows`: 처음 찾기는 그대로(모든 표·칸을 값으로) 하고, 찾은 표·칸을 기억해 다음부터는 질의 하나.
  - `DbInspector`: 읽기는 스레드마다 읽기 전용 연결 하나를 다시 쓴다(끊겼으면 다시 엶, 서버 쪽 prepared statement 없음). `schemaFixture`는 그대로 새 연결.
- 조건 한 번 확인: 3.4초 → 처음 418ms, 그 뒤 9~11ms. `AttachmentCleanupTest` 세 번 8/8, `attachment` 208, `audit` 99, `privacy` 141 실패 0.
- 메인이 본 것: `DbInspector`는 테스트 문맥마다 하나이고 Postgres 컨테이너도 문맥마다 하나라, 문맥이 닫히면 그 연결은 버려진다(닫지는 않는다). 남는 것은 끊긴 연결 객체뿐이라 그대로 두었다.

## 5. 브라우저

웹 탭(`popdesk:s10b-i-web`, 1280·820px, 밝게·어둡게): C-9 작업 없음, 대화상자(선택 둘), 진행 중에서 실패로 바뀌는 다시 읽기, 409 문장, 완료와 내려받기(ZIP을 풀어 문의 2, 첨부 5, 휴지통 문의는 없음), C-7 알림 문장 셋과 링크, C-11 "자동 정리"(SQL로 넣은 줄), C-12 새 종류 둘. 가로 넘침 없음, 콘솔 오류 0.

메인(`popdesk:test`, 고객사 ACME, 고객 하나, 문의 둘과 첨부, Chrome DevTools MCP):

| 본 것 | 폭과 테마 | 결과 |
|---|---|---|
| 앱을 내보내기 도중 다시 띄움 | | 로그 "1 interrupted, 1 files deleted", 줄은 "실패 / 앱이 다시 시작되어 멈췄습니다." |
| 상태 여섯 줄(완료 둘, 기간 지남, 실패 DISK·UNEXPECTED·INTERRUPTED) | 1280 밝게, 820 어둡게 | 배지 톤과 까닭 문장이 `screens.md`대로. 기간 지남은 SQL로 끝난 시각을 25시간 당겨 만들고, DISK는 SQL로 까닭만 바꿨다 |
| 파일을 지운 완료 줄의 내려받기 | 1280 밝게 | 구역 안 Alert "파일이 없습니다. 기간이 지났거나 지워졌습니다." 콘솔에는 HEAD 404 하나 |
| 완료 줄의 내려받기 | 820 어둡게 | `popdesk-ACME-2026-10-18.zip`(UTC 날짜). 문의 2, 댓글 2, 첨부 2, 고객 1, 빠진 파일 없음. 감사 `COMPANY_EXPORT_DOWNLOADED` 1 |
| C-7 알림함 | 820 어둡게 | 끝남 셋, 실패 둘, 자동 정리 켜짐 하나 |

- UNEXPECTED 둘은 메인이 `comment` 표를 잠가 둔 채 만든 것이다(잠금 시간 초과).
- 스크린샷: `C:\Users\minju\.claude\popdesk\shots\s10b-main\`. 설치는 `down -v`로 지웠다.
- 메인이 보지 않은 것: 503(잠금을 잃게 만들 수 없었다), 대화상자의 503 문장(웹 테스트만).

## 6. 메인이 코드로 본 것

- 스냅샷: `CompanyExportWriter.write`가 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서 고객 목록, 문의 목록, 문의마다 `issue.json`·`comments.json`을 쓰고 첨부 행만 모은다. 트랜잭션을 닫은 뒤 첨부 바이트를 읽는다(5절 11).
- 503: `CompanyExportService.create`가 `lock.held()`가 거짓이면 `Retry-After: 5`로 거절한다. 실행 스레드도 쓰기 전에 한 번 더 보고, 잠금이 없으면 `INTERRUPTED`로 끝낸다.
- 뜰 때 정리: `afterPropertiesSet`(싱글턴 만들 때)이라 웹 서버가 요청을 받기 전이다. `JdbcClient`가 Flyway 뒤라 마이그레이션 뒤이고, 단일 실행 잠금은 Flyway 전에 잡혀 다른 앱의 작업을 끊을 일이 없다.
- 내보내기 한 번에 하나: 앱 안 플래그를 행 쓰기 전에 잡고, 끝 UPDATE 뒤 놓는다. DB 유일 색인이 뒤를 받친다.

## 7. 넘긴 일

- 앱을 다시 띄워 끊긴 내보내기(`INTERRUPTED`)는 실패 알림을 남기지 않는다. AC-06이 알림을 적지 않았고, 화면 목록에서는 보인다.
- 자동 정리 켜짐 알림이 대상 0건일 때도 "0건을 휴지통으로 옮깁니다"로 나간다(`screens.md` 문장 그대로).
- 계약 `createCompanyExport` 설명의 검사 차례에 503 자리가 없다(conventions에는 적었다).
