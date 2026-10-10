# 첫 묶음 C-10 검증 결과

`design/process.md` C-10의 검증을 합친 `main`(2f5a993부터)에서 돌린 결과와, 메인이 정한 것을 적는다. 사람 확인이 필요한 것은 끝 절에 따로 둔다.

## 1. 숨긴 E2E는 통과해서 서버 테스트로 옮겼다

- 2f5a993 이미지에서 `popdesk-holdout`의 두 클래스 14개가 모두 통과했다. 옮긴 뒤 레포에서 다시 돌려도 통과했다(0f7aa1d).
- 브라우저 없이 HTTP로만 도는 테스트라 `web/e2e/`가 아니라 `server/src/test/java/io/popdesk/e2e/`에 `deploy` 태그로 두었다. CI 이미지 잡이 deploy 테스트 뒤에 돌린다. `process.md`에 둘 곳을 적었다.

## 2. 픽스처 값 검사에서 테스트 입력만 따로 처리한 곳은 없었다

- `check-fixture-literals.py`가 부분 문자열로 비교해 1481건을 냈다. 문자열 전체로 비교하고 story·lock·eslint 설정을 빼게 고쳤다(846e1dc). 남은 122건은 화면 문구(screens.md), 문서 절 이름, 로그 이름, 주석이다.

## 3. 웹훅 메시지는 en·ko 스냅샷으로 고정했다(S0-AC-75)

- `notify/__snapshots__/`의 네 파일(4074629). 제목의 줄바꿈과 따옴표로 한 줄 만들기와 JSON 이스케이프도 고정한다. 글은 `New issue {key} from {code}: {title}\n{link}`, `새 문의 {key} (고객사 {code}): {title}\n{link}` 꼴이다.

## 4. 변이 테스트

- 웹 Stryker(탭 `s0-v-mut-web`, 보고 `tasks\s0-v-mut-web-report.md`): 동작 테스트 24파일을 더했다(새 파일만, 잠금).
  - Stryker vitest 러너 10.0.0이 vitest 5의 테스트 이름 형식과 맞지 않아 모든 변이가 테스트 0개로 Survived가 됐다. `pnpm patch`로 고쳤다(`web/patches/`, 업스트림이 고치면 지운다).
  - ui 전체 48.37% → 81.25%. C-4 껍데기 때문에 처음부터 통과를 허용한 컴포넌트를 모두 포함한다. 파일별 점수는 탭 보고에 있다.
  - console 56.90% → 96.90%, portal 57.26% → 96.71%. 판단이 걸린 `src/app` 여섯 파일만(sessionContext, result, messages, attachments, paging, navigate). 화면 파일은 한 패키지에 12시간 넘게 걸려 미뤘다.
  - 운영 코드는 맞고 테스트가 빠졌던 곳: 마크다운 링크 허용 목록·`<주소>`·맨 주소 정규식의 `^`, `safeNext`의 제어 문자 검사와 `/login`·`/invite`로 되돌아가기 막기. 지금은 테스트가 잡는다.
- 서버 PIT: 웹이 끝난 뒤 돈다.

## 5. 보안 검사 도구는 Tomcat·Jackson 패치를 찾았고 올렸다

도구는 Docker 이미지로 돌렸다(로컬에 설치하지 않음). 처음에는 메모리 부족으로 끊겨, PC를 다시 켠 뒤 돌렸다.

- trivy(이미지 `popdesk:test`): jar에서 CRITICAL 3(Tomcat 11.0.24: CVE-2026-65182, -65905, -68525), HIGH 10(Jackson 2.21.5와 3.1.5: CVE-2026-68497, -89407, -89425, -91776, -91777). Spring Boot는 4.1.1이 최신 GA라 pom 속성으로 덮어썼다(tomcat 11.0.26, jackson-bom 3.1.7, jackson-2-bom 2.21.7, 03d1dfe). OS 패키지(ubuntu 24.04)는 MEDIUM 14, LOW 4이고 HIGH 이상은 없다. 릴리스 때 이미지를 다시 만들어 한 번 더 돈다.
- gitleaks(커밋 132개 전체): 4건, 모두 테스트 파일의 가짜 초대 토큰이다. 실제 비밀값은 없다.
- osv-scanner(`web/pnpm-lock.yaml`, `server/pom.xml`): `qs` 6.15.1 하나(중간 등급 셋). Stryker가 끌고 오는 `typed-rest-client`의 의존성으로 개발 도구에만 쓰이고 배포물에 들어가지 않는다. 그대로 둔다.
- 코드로 본 보안은 6절에 있다.

## 6. 사람이 코드로 볼 곳은 다섯 영역으로 나눠 읽었다

C-7 보고 7절, `tasks.md` 6·7절, 구현 탭 보고의 "C-10에서 사람이 코드로 볼 곳"을 모두 읽었다(읽기 전용 에이전트 다섯, 영역: 인증·계정, 문의·첨부·경계, 알림·운영, 시작·배포, 웹). 대부분 괜찮음이었다. 고칠 것과 정한 것만 적는다.

### 이번 묶음에서 고친다

테스트를 먼저 쓰고(테스트 쪽 탭) 구현 탭이 고친다. 가벼운 경로(5 → 6 → 9 → 10).

| ID | 곳 | 문제 | 고치는 법 |
|---|---|---|---|
| V-1 | `web/packages/ui/src/markdown.tsx:168` 제목 정규식 | 10,000자 본문 한 줄(`# a` + 공백 + `#` + 공백 …)로 그리기 한 번에 18초. 고객 글 하나로 상담원 콘솔이 멈춘다 | 끝 자르기를 정규식 밖으로. 인용 중첩 깊이 상한도 같이 둔다 |
| V-2 | `web/console/src/screens/C10MemberList.tsx` NewLinkDialog | 보내는 중에 닫은 뒤 온 답이 링크를 남겨, 다음에 다른 계정 B를 열면 A의 초대 링크가 B 이름과 함께 나온다(MAIL-3 위반, 잘못된 사람에게 계정이 넘어감) | 닫혔거나 계정이 바뀌었으면 답을 버린다. `InviteDialog`를 다시 열 때 앞 링크가 보이는 것도 같이 막는다 |
| V-3 | `web/*/src/app/sessionContext.ts` `useToLogin` | 내려간 화면에 늦게 온 401이 `setMe(null)`을 불러 layout이 빈 화면에 갇힐 수 있다(코드로 추론) | `setMe`도 마운트된 동안에만 |
| V-4 | `ReceivedPriority.java:54` | 문의 등록 JSON 본문을 상한 없이 `readAllBytes`. 로그인한 고객 하나가 힙을 다 쓸 수 있다 | 상한(256KB)을 넘으면 413. 처음 정한 64KB는 NFD 한글 10,000자(약 90KB, S0-AC-42)를 막아 올렸다 |
| V-5 | `SendLoops.java:93-110` | `sender.attempt`가 RuntimeException을 던지면 시도 수가 안 바뀌어 그 행이 늘 맨 앞에 잡힌다. 채널 전체가 경보 없이 멈춘다 | `Attempt.failed("INTERNAL_ERROR")`로 다뤄 백오프·24시간 failed·경보를 탄다 |
| V-6 | `InvitationService.java:93`, `FirstAdminSetup.java:76` | `POPDESK_LOCALE`을 검사하지 않아 `ko-KR`이나 빈 값이 계정 언어가 되고 메일이 영어로 간다 | 시작할 때 `en`·`ko`만 받고, 틀리면 키 이름을 남기고 S0-AC-02처럼 readiness DOWN. 읽는 곳을 하나로. configuration.md에 적는다(공개 계약이라 전체 경로) |
| V-7 | `TrustedProxies.literal()` | `.:`처럼 IPv6 정규식에 맞는 값이 `InetAddress.getByName`의 이름 조회로 갈 수 있다(추측) | 테스트로 먼저 확인. 맞으면 첫 글자 검사 |
| V-8 | 첨부 경로 `{id}` | UUID 모양이 아니면 400 `INVALID_FORMAT`. conventions "Attachment paths"와 S0-AC-102는 "없는 ID"와 같은 404 | 문자열로 받아 파싱 실패면 404 |
| V-9 | `AttachmentService.storeFiled` | 문의 등록이 정리 작업과 겨뤄 지면 ②가 쓴 내용 행이 남는다(①~② 1시간 넘을 때만) | 등록 트랜잭션 실패 뒤 트랜잭션 밖에서 그 등록의 `pending` 아닌 첨부 내용을 지운다 |
| V-10 | `RestoreCommand.java:75-81` | 빈 DB 확인이 `information_schema.tables`라 권한 없는 표를 못 본다 | `pg_class`로 |
| V-11 | `InstanceLock.java:137,140` | DB가 내려가거나 다른 인스턴스가 잠금을 쥔 동안 warn이 1초에 한 줄 | 상태가 바뀔 때 한 번, 같은 상태면 1분에 한 번 |
| V-12 | `deploy/compose.yml:82,134,138` | `${POPDESK_DB_PASSWORD}`가 없으면 빈 값으로 넘어가 엉뚱한 곳에서 실패한다(문서는 기본값 없음). `pg_isready`가 소켓을 봐 초기화 중 임시 서버를 준비됨으로 볼 수 있다 | `${POPDESK_DB_PASSWORD:?…}`, `pg_isready -h 127.0.0.1` |
| V-13 | `server.tomcat.max-part-count` | Boot 기본이 10이면 첨부 9개 넘는 문의 등록이 500일 수 있다(추측) | 테스트로 먼저 확인. 맞으면 상한을 정해 413 |
| V-15 | `PasswordConfiguration`, `ResetPasswordCommand` | Argon2 메모리 16MiB(Spring Security 기본)가 OWASP 최소보다 작다 | 19MiB·반복 2·병렬 1로, 두 곳이 한 곳에서 만든다(9절, 사람이 정함) |
| V-14 | JDBC `logServerErrorDetail` | 운영자가 DB URL을 바꾸며 빠뜨리면 DB 오류 상세가 로그에 남는다 | Hikari `data-source-properties`와 `InstanceLock` 연결에도 넣는다 |

문서(메인이 고친다):

- `configuration.md`: 컨테이너 메모리 한도를 걸면 0.75GB 이상(힙은 한도를 따라 줄지 않는다), 필요한 Docker Engine 버전(`start_interval` 때문에 25 이상), TLS 1.2 이상과 HSTS는 프록시가 붙인다(threat-model.md 64줄이 운영 문서에 있다고 적었는데 없었다).
- `architecture.md` 7절: 잰 메모리 값.
- `deploy/upgrade/README.md`: 레지스트리 다이제스트 파일 이름, 견본은 릴리스 당일 만든다(실패 대기 행 7일 삭제 때문).

### 메인이 정한 것 (고치지 않거나 뒤로 미룬다)

- 첨부 내려받기 Content-Type을 허용 목록 밖이면 `octet-stream`으로: 미룬다. 지금 `attachment`·nosniff·CSP가 있다. 1번 묶음 첨부 미리보기 때 정한다.
- 문의 등록에 붙인 첨부를 첨부 횟수 제한(S0-AC-65)에 셀지: 1번 묶음에서 기준으로 정한다.
- 403 `CSRF_TOKEN_INVALID` 문구(지금 "권한이 없습니다"): 1번 묶음 screens에서 정한다. 드물다.
- `TextInput`이 NUL·짝 없는 서로게이트를 막지 않음(NUL은 500): 1번 묶음에서 conventions "Text input"에 규칙을 더한다. 감사 해시 사슬과 엮인다.
- 첨부 한도 경보가 첨부를 지울 때만 풀림: 1번 묶음.
- 화면 `assets/**` 캐시, 베이스 이미지 다이제스트 고정, `MigrationStart`의 Flyway 내부 클래스(올릴 때 컴파일이 깨져 바로 드러남): 릴리스 D에서 본다.
- 있는 계정과 없는 계정의 실패 응답 시간이 DB 쓰기만큼(몇 ms) 다름: 받아들인다. Argon2 시간 편차에 묻히고, IP 제한과 잠금이 있다.
- 잠금 연결 TCP keepalive, `SessionCleanup` N+1, 정리 cron이 잠금 밖에서 도는 Spring Session 기본 작업: 지금 규모에서는 그대로 둔다.

### C-10 범위를 줄였다 (2026-10-10 사람이 정함)

C-10이 길어져 0.1.0 전에 할 것을 줄였다.

- 이번에 했다: V-1~V-3(ca61b3f), V-4·V-5·V-6·V-12·V-15(e1bec3e까지 다섯 커밋). 그 밖에 웹 변이 테스트 결과 합치기, 보안 검사 도구, 핵심 흐름 브라우저 확인.
- 0.1.0 뒤, 1번 묶음 앞에 한다: V-7~V-11, V-13, V-14, 서버 PIT. 드물거나 로그 양 정도의 문제다. PIT는 Testcontainers 테스트라 웹보다 오래 걸려 릴리스 태그 뒤에 따로 돈다.

## 7. 브라우저로 핵심 흐름을 눌러 봤다

V-1~V-15를 합친 `main`의 이미지를 릴리스 compose와 `compose.dev.yml`(Mailpit)로 띄우고 `POPDESK_LOCALE=ko`로 Chrome DevTools에서 눌렀다.

- 관리자 로그인 → 고객사 만들기 → 고객 초대 → Mailpit의 초대 메일 링크로 수락(주소창에서 토큰이 지워짐) → 포털에서 문의 등록(마크다운, 첨부 300KB) → 콘솔에서 문의 열기, 첨부 내려받기(sha256 같음, `attachment`·nosniff·CSP 머리글) → 공개 답변 → 고객 메일(한국어, 답변 내용과 상담원 정보 없음) → 포털에 "지원팀" 답변이 보임.
- 마크다운: 제목, 굵게, 목록, 인용이 그려지고 `https` 링크만 링크가 된다. `javascript:` 주소는 글자로 남는다.
- 콘솔 오류는 로그인 전 `/me` 401뿐이다.
- 내용이 빈 문의가 등록된다. S0-AC-42가 내용에 상한만 두어 기준대로다.
- 시각 회귀 도구와 손 접근성 점검은 따로 돌리지 않았다. 화면 테스트의 axe 검사와 이 확인으로 갈음하고, 시각 회귀는 ci.yml의 "붙일 관문" 목록에 남아 있다.

## 8. C-10 동안 CI가 빨갰던 것과 고친 것

- 숨긴 E2E를 옮긴 0f7aa1d부터 이미지 잡이 실패했다. 레포 경로 기본값을 바꿨다고 적었지만 치환이 적용되지 않아 이 PC 경로가 남았고, 메인이 푸시 뒤 CI를 보지 않았다. a9ebf6d에서 고쳤다.
- E2E가 같은 JVM에서 먼저 돌며 JDK HTTP 클라이언트를 먼저 써서, 도움 클래스의 static 초기화로 걸던 `jdk.httpclient.allowRestrictedHeaders`가 듣지 않아 deploy 테스트가 실패했다. surefire argLine으로 옮겼다(e719e37).
- 잠금 잡은 한 푸시에 잠근 테스트와 운영 쪽 파일이 같이 있으면 실패한다. 웹 변이 탭의 커밋(테스트와 Stryker 설정·package.json)과 V-4~15 구현 푸시(잠금 목록과 운영 코드)가 그렇게 걸렸다. C-11에서 다룬다.

## 9. 사람이 정한 것 (2026-10-10)

- Argon2 매개변수: 메모리 19MiB, 반복 2, 병렬 1로 올린다(V-15). features.md AUTH-5를 고쳤다. 해시 문자열에 매개변수가 들어 있어 있던 해시도 맞는다.
- 믿는 프록시 기본값(ADR D-46): 기본값은 둔다. configuration.md TLS 절과 threat-model TM-28에 프록시 없는 사내망·Docker Desktop의 위험과 값을 좁히는 법을 적었다.

## 10. 릴리스 D에서 본 것 (2026-10-10)

- 베이스 이미지 다이제스트: `Dockerfile`의 세 `FROM`을 태그와 다중 아키텍처 인덱스 다이제스트로 고정했다. `v0.1.0` 태그를 나중에 다시 빌드해도 같은 층에서 시작한다. `apt-get`으로 넣는 `postgresql-client-16`은 고정하지 않았다.
- 화면 `assets/**` 캐시: 1번 묶음으로 미룬다. 지금은 모든 화면 응답이 `no-store`라 느릴 뿐 틀린 동작은 없고, 바꾸려면 보안 머리글 기준(S0-AC)을 고쳐야 한다.
- `MigrationStart`의 Flyway 내부 클래스(`FlywayMigrateException`): 그대로 둔다. Flyway를 올리다 이름이 바뀌면 컴파일이 깨져 바로 드러난다.
- 이미지는 ghcr에 올리지 않았다(사람이 정함). 경위와 올릴 때 할 일은 `process.md` D에 있다.
