# 첫 묶음 작업 목록

> `process.md` B "나눠 쓰기"와 C-8의 분해다. 한 줄이 탭 세션 하나다. 묶음이 끝나면 지운다(D-27).
> 수용 기준 104개(`acceptance.md` 4절)를 테스트 세션 열 개에 하나씩만 맡겼다. 구현 작업(C-8)은 C-7 점검이 끝난 뒤 테스트 파일 이름을 넣어 다시 고친다.

## 1. 순서는 계약, 테스트 바탕, 테스트 세 물결, 실패 확인, 점검, 구현이다

| 차례 | 탭 | 역할(`start-tab.ps1 -Role`) | 앞에 끝나야 하는 것 |
|---|---|---|---|
| 1 | `s0-c4-server`, `s0-c4-web` | other | 없음. 둘은 같이 돈다 |
| 2 | `s0-t0-base` | tests | `s0-c4-server` 병합 |
| 3 | `s0-t-auth`, `s0-t-proj`, `s0-t-issue`, `s0-t-web-portal` | tests | `s0-t0-base` 병합 |
| 4 | `s0-t-att`, `s0-t-notify`, `s0-t-ops`, `s0-t-web-console` | tests | 3의 서버 탭 병합 |
| 5 | `s0-t-start`, `s0-t-boundary`, `s0-t-deploy`, `s0-t-e2e` | tests | 4의 서버 탭 병합 |
| 6 | 메인이 C-6 실패 확인 | 스크립트 | 5 병합 |
| 7 | `s0-c7-review`, 사람 승인 | other | C-6 잠금 목록 커밋 |
| 8 | 구현 탭(3절) | impl | C-7 승인 |

- 한 물결에 서버 테스트 탭은 셋까지다. Testcontainers를 쓰는 Maven 실행이 셋을 넘으면 16GB에서 버티지 못한다. 웹 테스트 탭(Vitest)은 가벼워서 넷째로 붙인다.
- 2를 따로 둔 까닭: 모든 서버 테스트가 같은 도움 코드(호스트별 HTTP 클라이언트, 세션·CSRF, API로 만드는 픽스처, GreenMail, 웹훅 받는 쪽, 로그 잡기, 고정 `Clock`)를 쓴다. 이것을 여러 탭이 따로 만들면 같은 파일을 고치거나 같은 일을 다르게 짠다.
- 물결 사이를 병합으로 끊는 까닭: 뒤 물결이 앞 물결의 픽스처 쓰는 법을 보고 맞춘다. 같은 물결의 탭끼리는 파일이 겹치지 않는다(2절).

## 2. 테스트 세션은 수용 기준 절 단위로 나누고, 파일은 패키지로 가른다

서버 테스트는 `server/src/test/java/io/popdesk/` 아래 탭마다 패키지 하나를 쓴다. 도움 코드 패키지 `support`는 `s0-t0-base`만 고친다. 뒤 탭이 도움 코드를 더 바라면 자기 패키지에 두고 보고에 적는다. 메인이 다음 물결 전에 `support`로 옮길지 정한다.

| 탭 | 패키지·경로 | 기준 | 수 |
|---|---|---|---|
| `s0-t0-base` | `support` | 없음. 도움 코드와 그것이 도는지 보는 테스트 하나 | 0 |
| `s0-t-start` | `start` | 4.1 S0-AC-01~12, 4.12 S0-AC-103 | 13 |
| `s0-t-auth` | `auth` | 4.2 S0-AC-13~20, 4.3 S0-AC-21~28 | 16 |
| `s0-t-proj` | `project` | 4.4 S0-AC-29~32, 4.5 S0-AC-33~40·101 | 13 |
| `s0-t-issue` | `issue` | 4.6 S0-AC-41~50, 4.7 S0-AC-51~55 | 15 |
| `s0-t-att` | `attachment` | 4.8 S0-AC-56~63·65·99·102 | 11 |
| `s0-t-notify` | `notify` | 4.9 S0-AC-66~77 | 12 |
| `s0-t-ops` | `ops` | 4.10 S0-AC-78·80~86·100 | 9 |
| `s0-t-boundary` | `boundary` | 4.12 S0-AC-92·93·98 | 3 |
| `s0-t-deploy` | `deploy`(업그레이드 틀과 견본 확인 포함) | S0-AC-64·79·88~91·95~97·104 | 10 |
| `s0-t-web-portal` | `web/portal/src/**/*.test.tsx` | S0-AC-87, 화면 P-1~P-5와 2절 공통 상태 | 1 |
| `s0-t-web-console` | `web/console/src/**/*.test.tsx`, `web/packages/ui/**/*.test.tsx` | 화면 C-1~C-4, C-8~C-10, C-18, 5절 공용 컴포넌트 | 0 |
| `s0-t-e2e` | `C:\Users\minju\popdesk-holdout\` | 4.12 S0-AC-94 | 1 |

합은 104다. 나눈 기준:

- S0-AC-64(동시 10MB 부하)는 첨부지만 실제 앱 이미지를 `-Xmx384m`로 띄워야 해서 이미지·compose를 다루는 `s0-t-deploy`에 둔다. S0-AC-79(Grafana), S0-AC-91(계약 파일 비교)도 같은 까닭이다. S0-AC-91은 바닥의 `OpenApiFilesTest`와 CI가 이미 하는 일이라 C-6에서 `red-allow.txt`에 들어갈 후보다.
- S0-AC-87은 화면의 413 안내라 포털 테스트에 둔다. 운영 문서의 프록시 설정 문단은 테스트가 아니라 C-7 점검에서 사람이 본다.
- S0-AC-92·98(고객사 경계, 상담 쪽 정보)은 문의·댓글·첨부·메일을 모두 거친다. 그래서 그 셋을 쓴 뒤인 5번째 차례에 둔다. 경계 테스트 목록(S0-AC-93)은 `s0-t-boundary`가 모든 엔드포인트를 계약 파일에서 읽어 만든다.
- 웹 쪽 기준 ID가 없는 화면 테스트는 이름에 화면 ID(`P-4`, `C-10`)를 단다. `trace.py`는 기준 ID만 세므로 웹 화면 테스트의 빠짐은 C-7에서 `screens.md`와 대 본다.

모든 테스트 탭이 지킬 것(지시서에 넣는다):

- HTTP와 생성된 클라이언트로만 시험한다. 서버 내부 클래스는 참조하지 않는다. DB를 봐야 하는 것(스키마, 잠금, 행 상태)만 예외로 JDBC로 본다.
- 모호한 기준은 추측하지 않고 자기 보고 파일의 "질문" 절에 적는다. `questions.md`는 메인이 보고를 모아 쓴다(여러 탭이 한 파일을 고치지 않게).
- 자기 테스트만 돌린다. `check-test-style.py --all-strict`를 자기 경로에 돌려 통과시킨다.
- 테스트는 계약의 501 위에서 돈다. 픽스처 단계의 실패도 단언 실패(`AssertionError`)로 나게 짠다. 그래야 C-6의 `check-red.py`가 "단언 실패"로 읽는다.

## 3. 구현은 모델부터 화면까지 일곱 탭이고, C-7 뒤에 테스트 이름을 채운다

`process.md` C-8. 같은 파일을 두 탭이 고치지 않게 마이그레이션은 한 탭이 모두 쓴다. 작업마다 "이 테스트들을 통과시킨다"로 적고, 테스트 클래스 이름은 C-6 잠금 뒤에 채운다.

| 차례 | 탭 | 맡는 것 | 통과시킬 기준 |
|---|---|---|---|
| 1 | `s0-i-schema` | 마이그레이션 전부(이 묶음의 모든 테이블), 앱 잠금, 기동 순서, 팀 1, 첫 관리자, `*_FILE`, 시작 로그, `Clock`, `tenant_id` 예외 목록(`server/README`) | S0-AC-01~12·103 |
| 2 | `s0-i-web` | 호스트 나누기, 관리 포트, 보안 머리글, 쿠키·CSRF, 믿는 프록시, 링크 만들기, 오류 본문·확인 순서·재시도, JSON 로그와 가리기 | S0-AC-13~20·85·86 |
| 3 | `s0-i-account` | 로그인·세션·잠금·수 제한, 고객사·배정, 초대·수락·링크 복사, 감사 로그와 정해진 JSON | S0-AC-21~40·83·100·101 |
| 4 | `s0-i-issue` | 문의·댓글, 범위를 받는 저장소 꼴 | S0-AC-41~55·84 |
| 4 | `s0-i-notify` | `notification` 모듈: 보내기 대기, 메일, 웹훅, 운영 경보, 시스템 상태, 정리 작업 기록, 지표 | S0-AC-66~78·80~82 |
| 5 | `s0-i-att` | 첨부 저장·내려받기·지우기·정리, 동시 수 상한 | S0-AC-56~63·65·99·102 |
| 6 | `s0-i-deploy` | Dockerfile, 릴리스·개발·관측 compose, Grafana 대시보드, `popdesk` 명령, 업그레이드 틀, 견본 데이터 생성기, 운영 문서의 프록시 문단 | S0-AC-64·79·87(문서)·88~91·95~97·104 |
| 7 | `s0-i-portal`, `s0-i-console` | 포털·콘솔 화면, 공용 컴포넌트 | 웹 테스트 전부, S0-AC-87 |
| 끝 | 메인 | 경계·상담 쪽 정보·빈 설치 E2E를 합친 `main`에서 돌림 | S0-AC-92~94·98 |

- 4의 둘은 같이 돈다. `s0-i-issue`는 `helpdesk`, `s0-i-notify`는 `notification` 패키지만 고친다. 댓글이 메일 대기 행을 넣는 자리는 `notification`의 공개 API이고, 그 인터페이스는 `s0-i-account`(초대 메일 대기 행)가 먼저 만든다.
- 마이그레이션을 한 탭에 몬 까닭: 버전 번호가 겹치지 않게 하고, 잠금 순서(`architecture.md` 4절)를 지키는 외래 키·제약을 한 사람이 같이 본다. 뒤 탭이 칸을 더 바라면 새 버전으로 더하고 이미 병합한 파일은 고치지 않는다(D-24).
- 화면 탭은 실제 API가 선 뒤에 돈다(C-9). 공용 컴포넌트(`web/packages/ui`)는 `s0-i-console`만 고치고, 포털 탭은 쓰기만 한다. 포털 탭이 먼저 필요하면 메인이 순서를 바꾼다.

## 4. C-4 계약 탭은 둘이고, 서버 탭의 엔드포인트 목록은 사용자에게 보인 뒤 병합한다

| 탭 | 하는 일 | 고치는 곳 |
|---|---|---|
| `s0-c4-server` | 이 묶음의 모든 엔드포인트를 컨트롤러 껍데기(경로, 요청·응답 타입, 본문은 501)로 더하고, 오류 `code` 이름(`screens.md` 1절의 "가칭")을 정해 `conventions.md`에 적고, `./mvnw -Popenapi test`로 계약 파일 둘을 만들고 클라이언트를 다시 만든다. OpenAPI `info.title`도 정한다 | `server/src/main/**`(테스트 아님), `docs/integrate/`, `web/packages/api/src/generated/**` |
| `s0-c4-web` | `screens.md` 5절 공용 컴포넌트의 props·story 껍데기와 두 앱의 화면 경로 껍데기 | `web/packages/ui/src/**`, `web/portal/src/**`, `web/console/src/**`(테스트 파일 아님) |

- 계약은 공개 계약이라 전체 경로다(AGENTS.md). 메인은 `s0-c4-server`의 엔드포인트 표를 사용자에게 보이고 확인을 받은 뒤 병합한다.
- 껍데기의 컨트롤러는 Spring Security 없이 둔다. 보안 설정은 S0-AC-13~20의 구현이라 `s0-i-web`이 한다(바닥과 같은 까닭, `process.md` B).
- `s0-c4-web`은 생성된 클라이언트를 쓰지 않는 props로 짠다. 두 C-4 탭이 같은 파일을 고치지 않게 하려는 것이다. 화면 탭(3절 7)이 둘을 잇는다.

## 5. 테스트 도움 코드가 정한 것은 구현이 받쳐야 한다

`s0-t0-base`가 만든 `server/src/test/java/io/popdesk/support/`(쓰는 법은 같은 폴더 `README.md`)가 아래를 가정한다. 구현 탭 지시서에 넣는다.

- 설정은 Spring `Environment`에서 키 이름 그대로 읽는다(`${POPDESK_PORTAL_HOST}`나 `environment.getProperty`). `System.getenv()`를 바로 읽거나 `@ConfigurationProperties`의 느슨한 묶기에만 기대면 테스트 값이 닿지 않는다. 모르는 키 경고(S0-AC-06)와 `*_FILE`(S0-AC-04)도 `Environment`의 속성 출처를 훑는다.
- 시각은 모두 주입한 `Clock` 빈으로 읽는다. 앱의 `Clock` 빈은 `@Primary`가 아니고 이름이 `testClock`이 아니다. DB 기본값 `now()`로 업무 시각을 정하지 않는다.
- Host는 포트를 떼고 호스트 이름만 비교한다.
- CSRF는 쿠키 `XSRF-TOKEN`, 머리글 `X-XSRF-TOKEN`이고 쿠키 값을 그대로 받는다(conventions "Sessions and CSRF").
- 메일 TLS는 JVM 기본 신뢰 저장소를 쓴다. 메일 연결에 따로 신뢰 저장소나 `mail.smtp.ssl.trust`를 두지 않는다.
- 로그는 logback `ConsoleAppender`로 표준 출력에 쓴다.
- 포트 465의 암묵적 TLS(S0-AC-74 앞부분)는 앱을 JVM 안에서 띄우는 테스트로는 시험하지 못한다(빈 포트에 뜨는 테스트 서버). `s0-t-deploy`가 앱 이미지와 같은 Docker 네트워크의 메일 서버 컨테이너(465)로 시험한다.
- C-6의 `red-allow.txt`에 `SupportSelfTest` 15건을 "도움 코드 자기 시험, 업무 동작을 보지 않는다"로 올린다.

## 6. 둘째 물결 테스트 질문으로 정한 것

구현 탭 지시서에 넣는다. 수용 기준 문장을 바꾼 것은 S0-AC-100(RFC 8785 키 순서, 소수 여섯 자리)뿐이고 나머지는 기준을 읽는 법이다.

- 크기 단위: MB·GB는 2^20·2^30바이트다(Spring `DataSize`와 같다). 10MB는 10,485,760바이트, multipart 상한은 그보다 조금 크게 둔다.
- 정기 작업(정리 작업 셋, 보내기 대기의 `failed` 지우기, 운영 경보): 실제 시간으로 짧은 주기(몇 초)마다 깨어나고, 돌 때가 됐는지는 주입한 `Clock`으로 마지막 실행 시각과 견줘 정한다. 테스트는 `clock.advance` 뒤 `Wait`로 기다린다.
- 정리 작업 기록(S0-AC-82)은 작업마다 한 행을 고쳐 쓴다. 작업 이름은 `outbox-failed-cleanup`, `attachment-cleanup`, `session-cleanup`이다.
- `failed` 행은 `failed`가 된 시각에서 7일 뒤 지운다(S0-AC-68).
- 운영 경보(S0-AC-80)의 "풀림"은 `failed` 행이 0건이 됨, 첨부 합이 80% 아래로 내려감이다. 풀리지 않으면 마지막 항목에서 24시간(주입한 `Clock`)마다 한 건 더 남긴다.
- 실패 주입: 운영 코드에 테스트 전용 엔드포인트나 설정 키를 두지 않는다. 테스트는 `DbInspector.schemaFixture`로 이름이 `test_`로 시작하는 트리거를 걸어 DB 쓰기를 실패시킨다(순번 직전 실패, ③ 실패로 남는 `pending`, 저장소 지우기 실패로 남는 `purging`, 대기 행 뒤 업무 실패, 일부러 낸 500). 그 테스트는 트리거를 끝에서 지우거나 자기 컨텍스트를 쓴다.
- 업무 행을 SQL로 바꾸는 것은 하나만 허용한다: 고객의 고객사 소속 빼기(PROJ-4 빼기는 1번 묶음). S0-AC-69·92의 "볼 수 없게 됨"을 만든다.
- S0-AC-63의 "`pending`으로 남음"은 ① 뒤에 나는 실패만이다. ① 전에 끝나는 검증 실패(400)는 첨부 행을 만들지 않는다.
- 첨부 쓰기 규칙(S0-AC-56의 `setBytes` 금지)은 첨부 저장소 패키지에만 건다. 패키지 이름은 `s0-t-ops`가 제안한 이름(`ScopedReadRulesTest`)을 따른다.
- 감사(S0-AC-83): 일의 종류는 `FIRST_ADMIN_CREATED`, `COMPANY_CREATED`, `AGENT_ASSIGNED`, `INVITATION_CREATED`, `INVITATION_LINK_CREATED`, `SIGN_IN_SUCCEEDED`, `SIGN_IN_FAILED`, `ACCOUNT_LOCKED`, `ADMIN_PASSWORD_RESET`, `BACKUP_CREATED`, `DATABASE_RESTORED`. 없는 이메일의 로그인 실패는 감사 행을 남기지 않는다. 있는 계정의 실패는 한 사람을 그 계정으로 둔다.
- 지표 이름과 `CanonicalAuditJson`의 클래스·시그니처, 범위 타입(`io.popdesk.helpdesk.Viewer`)은 `s0-t-ops` 보고의 제안을 따른다. `s0-t-deploy`의 Grafana 대시보드도 같은 지표 이름을 쓴다.
- 댓글 알림 메일 제목에는 문의 제목이 들어간다(S0-AC-71). 문의 제목의 줄바꿈은 막지 않는다.
- 웹훅 메시지 스냅샷 파일(S0-AC-75)은 C-10에서 메인이 고정한다.
- C-7 점검에서 사람이 코드로 보는 것: multipart 상한과 `file-size-threshold` 0(S0-AC-99), 40P01 재시도(S0-AC-85, 55P03만 테스트가 있다), 보낼 때 주소·제목을 읽음(S0-AC-66).

