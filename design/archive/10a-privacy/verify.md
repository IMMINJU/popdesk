# 10a 묶음(계정 내보내기와 익명화) 검증 (C-10)

> 2026-10-18~19 메인이 이 PC에서 했다. GitHub Actions 10월분을 다 써서 CI는 돌지 않았다.
> C-10은 닿은 부분만 돈다(`process.md` C-10 첫 줄). 뺀 것은 2절 끝에 적었다.
> 숫자는 각 실행의 로그(`~\.claude\popdesk\tasks\s10a-ci\`, `s10a-oom\`)와 탭 보고에서 옮겼다.

## 1. 구현 탭 셋이 끝났고 맡은 테스트는 모두 통과한다

| 탭 | 맡은 것 | 결과 |
|---|---|---|
| `s10a-i-server` | `pending.txt` 서버 클래스와 deploy `PrivacySampleTest` | 77개 통과, `PrivacySampleTest` 통과. 같이 돈 `start`, `audit`·`helpdesk`·구조 검사, `auth`·`photo`·`trash`, `project`·`manager`·`boundary`, `notify`에서 실패 0. 10만 건 익명화 약 10초(이 탭의 PC 상태), 그동안 로그인 20번 모두 성공 |
| `s10a-i-web` | `pending.txt` 웹 줄과 story | console 1359, portal 406, app 445, ui 753, api 91, story 실행기 713(`Screens10a.overflow` 24 포함), typecheck, lint, build, 포털 번들 검사 통과. dup 5.76% → 5.69%. 공용 컴포넌트 API는 그대로 |
| `s10a-i-tamper` | S10A-AC-37(끊긴 사슬 거절), 서버만 | `AnonymizeTamperedChainTest` 4, `privacy` 73, `audit` 99 통과. 확인을 더한 10만 건 익명화 약 21초(같은 PC에서 옛 코드 18초) |

- 서버 탭이 찾은 잠긴 테스트 잘못 하나: `AnonymizeRaceTest.retentionDuringRecalculation`은 초대 수락이 감사 항목을 남기지 않아 그 사람이 한 사람인 항목이 없었다. 메인이 로그인 한 번을 더해 다시 잠갔다(`bbf3421`).
- 서버 탭의 질문(재계산이 옛 사슬의 변조를 새 hash로 덮는다)은 crosscheck 뒤 "끊긴 사슬이면 익명화를 거절한다"로 정했다(`acceptance.md` 5절 11, D-62 끝 문장). 테스트 탭(`s10a-t-tamper`)이 먼저 쓰고 구현 탭이 구현했다.
- 순수 계산 둘의 단위 테스트(`AuditAnonymizerTest`, `matchesEmail.10a.test.ts`)는 테스트 쪽 서브에이전트가 본문을 열지 않고 시그니처와 주석만 보고 썼다(`c74a791`).
- `pending.txt`는 비었다(`39277f0`).

## 2. 이 PC에서 돈 것 (닿은 부분만)

| 단계 | 무엇 | 결과 | 시간 |
|---|---|---|---|
| gates | 잠금, 테스트 꼴, `tenant_id`, 잠긴 경로, gitleaks, 구조 검사 다섯 | 통과(`50bbb08`) | 183초 |
| web | `local-ci.sh web` 전체 | 통과(`f22e1bc`). console 99파일 1374, ui 753, app 445, portal 406, api 91, story 713 | 695초 |
| server-openapi | 계약과 생성 클라이언트 diff | 통과 | 51초 |
| image | `popdesk:test` 빌드 | 통과 | 74초 |
| 서버 묶음 | 닿은 패키지를 `local-ci.sh` 묶음 나눔대로 | 아래 | |
| deploy | `PrivacySampleTest`, `UpgradeHarnessTest`, `CommandAuditChainTest`, `UpgradeAuditChainTest`, `ResetPasswordTest` | 통과 | 783초 |
| 숨긴 E2E | `PrivacyFlowTest`, `AuditFlowTest`, `PeopleFlowTest` | 통과 | 138초 |

서버 묶음:

| 묶음 | 결과 | 시간 |
|---|---|---|
| p2 `audit`, `ops` | 278 통과 | 12분 |
| p3 `auth`, `start`, `photo`, `trash` | 364 통과(두 번째 실행. 첫 실행의 실패 하나는 아래) | 22분 |
| p4 `boundary`, `manager`, `inbox` | 289 통과 | 6분 |
| p5 `project`, `helpdesk`, `notification` | 3,469 통과 | 5분 |
| p1 `notify`, 루트, `privacy` | 306개가 실패 없이 돈 뒤 테스트 JVM 힙 부족(두 번 모두) | 40~58분 |
| server-4 새 나눔(`issue`, `inbox`, `boundary`, `manager`, `label`, `privacy`) | 742 통과 | 15분 |
| server-1 새 나눔(`notify`, 루트) | 235개가 실패 없이 돈 뒤 힙 부족(두 번) | 17~22분 |
| server-1, 아래 고침 뒤(`203fc5a`) | 236 통과 | 11분 |
| `privacy`와 `ProductionCodeRulesTest`만 | 모두 통과 | 3분 |

- 첫 검증 실행이 메모리 부족으로 끊긴 줄 알고 다시 띄웠는데, 첫 실행은 끝까지 돌았다. 두 실행이 `heavy.sh` 줄에 함께 서서 두 번째의 web이 2시간 기다렸다. 결과는 두 벌 모두 같다.
- p3 첫 실행의 실패 하나는 두 번째 실행에서 지났다. 두 번째 실행이 같은 로그 파일을 덮어써서 어느 테스트였는지 보지 못했다. 두 실행이 겹쳐 돈 때였다.

### 힙 부족은 닫힌 문맥이 남아서다

이번 묶음에서 새로 생긴 문제가 아니다. 8b까지 같은 server-1(236개)이 11~12분에 지났고, 9와 10a로 테스트 문맥이 늘면서 512m를 넘었다. 9 묶음 C-10의 `ops` 힙 부족, 10a 서버 탭의 `TransactionalPlacementTest` 힙 부족도 같은 꼴이다.

- 1분마다 힙을 찍었다. notify가 도는 동안 살아 있는 객체가 221MB에서 327MB로 계속 늘었고, 끝에는 `HikariDataSource` 13~14개, Tomcat `NioEndpoint` 26~28개가 남았다. 풀은 14개가 열리고 11개가 닫혔으니(문맥 캐시 3개) 닫힌 문맥이 치워지지 않은 것이다.
- 힙 덤프를 서브에이전트가 파이썬으로 읽었다. 닫힌 부모 문맥 8개 모두 같은 사슬로 남았다: 관리 포트(`management.server.port`)의 자식 문맥 Tomcat의 대기 스레드(`container-N`) → 자식 문맥 → 부모. 자식은 멈췄지만(`running=0`) 닫히지 않았다(`closed=0`).
- 아니라고 확인한 것: Spring Boot의 전역 지표 registry(`management.metrics.use-global-registry=false`로 돌려도 같다), 테스트 문맥 멈춤(`spring.test.context.cache.pause=never`로 돌려도 같다).
- 고친 것: 테스트 쪽에 `support/ManagementContextCloser`(관리 문맥 설정, 앱 문맥이 닫히면 관리 문맥을 닫는다)와 등록 파일 하나. 다섯 클래스로 돌려 보니 밀려난 문맥마다 자식을 닫았고, 끝의 스레드 수가 살아 있는 문맥 3개와 맞았다.
- 고친 뒤 notify 뒤쪽에서 뜬 힙 덤프: 닫힌 문맥 16개 모두 GC 뿌리에서 강한 참조로 닿지 않는다(부드러운·약한 참조만 남아 힙이 모자라면 치워진다). 히스토그램의 `HikariDataSource` 수가 줄지 않은 것은 그래서다.
- server-1을 512m 그대로 다시 돌려 236개 통과, 11분(8b의 12분과 비슷하다). 힙 상한은 올리지 않았다.
- 이 고침은 테스트 바탕(잠긴 `support`)이라 메인이 넣고 다시 잠갔다. 운영의 관리 포트 동작은 바뀌지 않는다(운영 JVM은 앱 문맥과 함께 끝난다).

뺀 것: 닿지 않은 서버 패키지(`issue`, `label`, `attachment`, `stats`, `support`, `sla`, `due`, `task`, `link`, `branding`), deploy의 나머지 견본·관측 테스트, 숨긴 E2E의 다른 묶음 클래스, deps.

## 3. 메인이 브라우저로 본 것

진짜 서버(`popdesk:test`, `f22e1bc`), API로 넣은 견본(관리자, 고객사 ACME, 상담원 하나, 고객 셋 가운데 하나는 미리 익명화, 수락 전 초대 하나, 문의 둘과 댓글). SMTP는 Mailpit. Chrome DevTools MCP.

| 화면 | 폭과 테마 | 결과 |
|---|---|---|
| C-10 목록 | 1280 밝게 | 익명 줄은 "삭제된 사용자", `deleted-4@invalid`, 회색 "익명화됨", 머리글자 "삭" |
| C-10 줄 메뉴 여섯 경우 | 1280 | 수락 전, 고객 둘, 익명, 상담원, 나. `screens.md` 표와 항목·차례가 같다(SMTP가 있어 초대 링크 복사는 없음) |
| 익명화 대화상자(고객) | 1280 밝게 | 제목, "되돌릴 수 없습니다.", 바뀌는 것 넷, 확인 칸. 첫 초점은 확인 칸. 빈 칸이면 단추가 `aria-disabled`이고 눌러도 요청이 없다. `  CHOI.Customer@sample.TEST  `을 치면 풀리고 Enter로 보냈다. 줄이 익명으로 바뀌고 "익명화했습니다", 초점은 그 줄의 메뉴 단추 |
| 익명화 대화상자(상담원, 담당 0) | 820 어둡게 | 담당 조각 숨김, 가로 넘침 없음 |
| 정보 내보내기 | 820 어둡게 | 화면이 `HEAD` 뒤 내려받기로 넘기고 페이지는 그대로. `attachment; filename="popdesk-account-3.json"`, 칸 여섯 |
| C-12 | 1280 밝게, 820 어둡게 | 새 종류 셋, "시작 번호", "끝 번호", "마지막 hash"(차례도 맞음). hash는 줄을 바꿔 다 보인다. 820에서 표 698px가 틀 안. 무결성 확인 "모두 맞습니다. 항목 20건"(익명화 두 번 뒤) |
| C-4 ACME-1 | 820 어둡게 | 올린 사람과 댓글 작성자 "삭제된 사용자", 옛 이름 없음 |
| 포털 ACME-1(다른 ACME 고객으로) | 360 어둡게 | 같음. 넘침 없음 |
| C-9 ACME 소속 고객 | 820 어둡게 | 익명 고객 둘이 "삭제된 사용자 / 정지"(8절) |

- 콘솔 오류는 로그인 전의 401뿐이다. 설치는 `down -v`로 지웠다.
- 메인이 보지 않은 것(웹 탭이 봄): C-9 고객 찾기의 `anonymized=false`, 담당이 있는 상담원의 담당 비우기와 알림 문구, 옛 줄의 400 띠.

## 4. 변이 테스트

| 대상 | 만든 변이 | 잡음 | 살아남음 |
|---|---|---|---|
| 서버 PIT `AuditAnonymizer`(단위 테스트만) | 41 | 28 | 9, 닿지 않음 4 |
| 웹 Stryker `matchesEmail.ts` | 7 | 7 | 0 |
| 웹 Stryker `exportDownload.ts`(그 파일의 테스트만) | 30 | 23 | 2, 닿지 않음 5 |

서버의 살아남은 9와 닿지 않은 4:
- 줄 143·149·152: JSON 객체가 아닐 때 `null` 대신 빈 Map. 뒤의 계정 검사가 거짓이 되어 결과가 같다.
- 줄 110·111·121·135·137: "바뀌었나"를 늘 참으로. 값은 같고 다시 쓰기만 는다(같은 값의 hash라 사슬도 같다).
- 줄 68 `|` → `&`: 한 사람의 이메일만 이미 익명이고 이름은 옛 값인 항목에서 이름이 바뀌지 않는다. 그런 항목을 만드는 길이 지금 없다(익명 계정은 이름 고치기가 막혔고, 늦게 붙는 항목은 둘을 함께 익명으로 쓴다). 테스트 없음.
- `accountIds` 4: 통합 테스트(`AnonymizeAuditTest`의 늦은 항목)로만 지난다.

웹의 살아남은 2와 닿지 않은 5(Stryker는 화면 테스트 파일을 읽지 못하는 문제가 그대로라 손으로 넣어 C-10·C-12 테스트까지 돌렸다):
- 줄 28(확인 중인 주소면 같은 Promise): 손으로 넣으니 화면 테스트 1개가 잡았다.
- 줄 30(끝나면 지움): 화면 테스트 16개가 잡았다.
- 줄 38~39(연결이 끊겼을 때의 문구): 손으로 빈 글로 바꿔도 222개가 모두 지났다. 테스트 없음.

## 5. 메인이 코드로 본 것

- 재계산 두 부분: ①은 머리를 잡지 않고 2000행 묶음 `unnest` UPDATE, ②는 머리 `FOR NO KEY UPDATE` 뒤 그 사이 붙은 항목과 머리 hash. 계정 쪽(세션, 사진, 메일, 알림, 소속 요청)을 먼저 하고 ②를 트랜잭션의 마지막 쓰기로 두었다(머리를 가장 늦게 잡음).
- 옛 사슬 확인은 무결성 확인과 같은 `AuditChain.holds`/`canonicalIfHolds`를 지난다. 끊기면 그 묶음을 쓰지 않고 트랜잭션 전체를 되돌린다. 응답에는 `brokenSeq`만, 로그에는 seq만.
- 보관 작업(AC-22)은 `rechain.waiting(...)` 안에서 돌고 익명화는 `tryLock`이다. 테스트가 약해 코드로 봤다(`design.md` 0절 13).
- `OWN_ACCOUNT`는 ① 뒤 잠금 안에서 본다. 자기 계정이면 ①이 돌고 되돌아간다(결과는 같고 낭비만 있다).
- 웹: `startExport`는 같은 주소를 확인하는 동안 같은 Promise를 돌려준다. 화면 쪽 처리가 두 번 돌지만 이동은 한 번이다.

## 6. 구현이 틀려서 C-10에서 고친 것

없다.

## 7. 테스트가 틀려서 고친 것

| 커밋 | 무엇 | 찾은 곳 |
|---|---|---|
| `bbf3421` | `AnonymizeRaceTest.retentionDuringRecalculation`(1절) | 서버 구현 탭 |
| `203fc5a` | 테스트 바탕: 관리 문맥을 앱 문맥과 함께 닫는다(2절). 테스트를 고친 것이 아니고 바탕을 더했다 | 메인, C-10 |

C-7 보강은 서버 `b89ae52`, 웹 `20b1fbf`, 고침 `oldEmailInvitedAgain`·`lateEntryIsAnonymous`(tfix 탭).

## 8. 정한 것과 뒤로 넘기는 것

정한 것은 `acceptance.md` 5절, `design.md` 0절에 있다(모두 메인이 정함, 사용자 확인 전).

| 것 | 정한 것 |
|---|---|
| 릴리스 | 0.10.0(11월 CI 뒤, 0.9.0 다음). 노트 재료: 관리자가 계정 정보를 JSON 하나로 내보낸다(`GET`·`HEAD /api/console/accounts/{id}/export`, 동시 둘). 계정을 익명화한다(`POST .../anonymize`, 되돌릴 수 없음, 이름은 설치 언어의 "삭제된 사용자", 이메일 `deleted-{id}@invalid`, 사진·세션·초대·재설정·메일 대기·알림함을 지움). 감사 사슬을 다시 계산해 `AUDIT_RECHAINED`를 남기고, 사슬이 이미 끊겨 있으면 거절한다(409 `AUDIT_CHAIN_BROKEN`, `brokenSeq`). 익명 계정은 되살리는 동작이 막힌다(400 `ACCOUNT_ANONYMIZED`). 익명화는 한 번에 하나(409 `ANONYMIZATION_RUNNING`). 계정 응답과 `listAccounts`에 `anonymized`. 새 감사 종류 셋. `audit-verify.py`가 재계산 구간을 읽는다. 마이그레이션 V22 |
| 받아들인 것 | 4절 서버 줄 68, 웹 줄 38~39(테스트 없음). 5절 `OWN_ACCOUNT`의 낭비 |
| 넘김 | C-9 소속 고객 표의 익명 고객이 "정지"로 보인다(`CompanyMember` 계약에 익명 칸이 없음). 같은 표에서 익명 계정도 "담당자로 지정"이 되고 서버가 200으로 받는다(정지 계정과 같은 동작, `rules.md`의 막을 목록에 없음). 빈 확인 칸에서 공용 `Button blocked`의 모양이 약하다(불투명도 0.8, 커서 `progress`). 계약 글: `createInvitationLink` 400이 SMTP 404보다도 먼저라는 것을 적는다 |
| 가드레일 | `local-ci.sh`에서 `privacy`를 server-1에서 server-4로 옮겼다(server-1은 구조 검사가 끝에 돌아 힙을 가장 많이 쓴다) |

## 9. 단계마다 걸린 시간

| 단계 | 시각 | 걸린 시간 |
|---|---|---|
| 설계(C-1~3) | 10-18 ~16:57 | 인계 전 세션에서 함 |
| 계약(C-4) | 16:57~17:21 | 25분 |
| 테스트(C-5, 탭 넷) | 17:21~18:16 | 55분 |
| 실패 확인(C-6) | 18:02~18:16 | 단언 실패 132, 허용 2, 오류 0 |
| 점검과 보강(C-7) | 18:16~18:47 | 30분 |
| 구현(C-9, 탭 둘 같은 때) | 18:47~20:46 | 서버 약 3시간(읽기 포함, 앞 탭과 겹침), 웹 약 100분(브라우저 포함) |
| 끊긴 사슬 거절(테스트 탭, 구현 탭) | 20:21~21:12 | 50분 |
| 검증(C-10) | 10-18 21:10~10-19 04:00 | 약 7시간. 닿은 부분 실행 약 2시간 반(두 벌이 겹침), 힙 부족 원인 찾기 약 3시간(힙 덤프 둘, 실험 다섯, 서브에이전트 분석), 변이 확인 15분, 브라우저 20분 |
