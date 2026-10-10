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

- 웹 Stryker: 탭 `s0-v-mut-web`. 결과는 그 탭 보고(`tasks\s0-v-mut-web-report.md`)를 합칠 때 이 절에 옮긴다.
  - Stryker vitest 러너 10.0.0이 vitest 5의 테스트 이름 형식과 맞지 않아 모든 변이가 테스트 0개로 Survived가 됐다. `pnpm patch`로 고쳤다.
  - ui: 48.37% → 81.25%, 동작 테스트 18파일. 마크다운 링크 검사 정규식의 `^`를 빼도 잡는 테스트가 없었다(운영 코드는 맞았다). 지금은 잡는다.
  - console·portal은 화면 테스트가 무거워 한 패키지에 12시간 넘게 걸린다. 판단이 걸린 `src/app`의 여섯 파일(sessionContext, result, messages, attachments, paging, navigate)만 돌리고 나머지는 목록으로 남긴다.
- 서버 PIT: 웹이 끝난 뒤 돈다.

## 5. 보안 검사 도구는 메모리 부족으로 못 돌렸다

- trivy 이미지 검사가 메모리 부족으로 끊겼다. 변이 테스트가 끝나고 Docker를 다시 띄운 뒤 trivy·osv-scanner·gitleaks를 돌린다.
- 대신 6절의 코드 읽기에서 영역마다 보안 문제를 함께 봤다.

## 6. 사람이 코드로 볼 곳은 다섯 영역으로 나눠 읽었다

C-7 보고 7절, `tasks.md` 6·7절, 구현 탭 보고의 "C-10에서 사람이 코드로 볼 곳"을 모두 읽었다(읽기 전용 에이전트 다섯, 영역: 인증·계정, 문의·첨부·경계, 알림·운영, 시작·배포, 웹). 대부분 괜찮음이었다. 고칠 것과 정한 것만 적는다.

### 이번 묶음에서 고친다

테스트를 먼저 쓰고(테스트 쪽 탭) 구현 탭이 고친다. 가벼운 경로(5 → 6 → 9 → 10).

| ID | 곳 | 문제 | 고치는 법 |
|---|---|---|---|
| V-1 | `web/packages/ui/src/markdown.tsx:168` 제목 정규식 | 10,000자 본문 한 줄(`# a` + 공백 + `#` + 공백 …)로 그리기 한 번에 18초. 고객 글 하나로 상담원 콘솔이 멈춘다 | 끝 자르기를 정규식 밖으로. 인용 중첩 깊이 상한도 같이 둔다 |
| V-2 | `web/console/src/screens/C10MemberList.tsx` NewLinkDialog | 보내는 중에 닫은 뒤 온 답이 링크를 남겨, 다음에 다른 계정 B를 열면 A의 초대 링크가 B 이름과 함께 나온다(MAIL-3 위반, 잘못된 사람에게 계정이 넘어감) | 닫혔거나 계정이 바뀌었으면 답을 버린다. `InviteDialog`를 다시 열 때 앞 링크가 보이는 것도 같이 막는다 |
| V-3 | `web/*/src/app/sessionContext.ts` `useToLogin` | 내려간 화면에 늦게 온 401이 `setMe(null)`을 불러 layout이 빈 화면에 갇힐 수 있다(코드로 추론) | `setMe`도 마운트된 동안에만 |
| V-4 | `ReceivedPriority.java:54` | 문의 등록 JSON 본문을 상한 없이 `readAllBytes`. 로그인한 고객 하나가 힙을 다 쓸 수 있다 | 상한(64KB)을 넘으면 413 |
| V-5 | `SendLoops.java:93-110` | `sender.attempt`가 RuntimeException을 던지면 시도 수가 안 바뀌어 그 행이 늘 맨 앞에 잡힌다. 채널 전체가 경보 없이 멈춘다 | `Attempt.failed("INTERNAL_ERROR")`로 다뤄 백오프·24시간 failed·경보를 탄다 |
| V-6 | `InvitationService.java:93`, `FirstAdminSetup.java:76` | `POPDESK_LOCALE`을 검사하지 않아 `ko-KR`이나 빈 값이 계정 언어가 되고 메일이 영어로 간다 | 시작할 때 `en`·`ko`만 받고, 틀리면 키 이름을 남기고 S0-AC-02처럼 readiness DOWN. 읽는 곳을 하나로. configuration.md에 적는다(공개 계약이라 전체 경로) |
| V-7 | `TrustedProxies.literal()` | `.:`처럼 IPv6 정규식에 맞는 값이 `InetAddress.getByName`의 이름 조회로 갈 수 있다(추측) | 테스트로 먼저 확인. 맞으면 첫 글자 검사 |
| V-8 | 첨부 경로 `{id}` | UUID 모양이 아니면 400 `INVALID_FORMAT`. conventions "Attachment paths"와 S0-AC-102는 "없는 ID"와 같은 404 | 문자열로 받아 파싱 실패면 404 |
| V-9 | `AttachmentService.storeFiled` | 문의 등록이 정리 작업과 겨뤄 지면 ②가 쓴 내용 행이 남는다(①~② 1시간 넘을 때만) | 등록 트랜잭션 실패 뒤 트랜잭션 밖에서 그 등록의 `pending` 아닌 첨부 내용을 지운다 |
| V-10 | `RestoreCommand.java:75-81` | 빈 DB 확인이 `information_schema.tables`라 권한 없는 표를 못 본다 | `pg_class`로 |
| V-11 | `InstanceLock.java:137,140` | DB가 내려가거나 다른 인스턴스가 잠금을 쥔 동안 warn이 1초에 한 줄 | 상태가 바뀔 때 한 번, 같은 상태면 1분에 한 번 |
| V-12 | `deploy/compose.yml:82,134,138` | `${POPDESK_DB_PASSWORD}`가 없으면 빈 값으로 넘어가 엉뚱한 곳에서 실패한다(문서는 기본값 없음). `pg_isready`가 소켓을 봐 초기화 중 임시 서버를 준비됨으로 볼 수 있다 | `${POPDESK_DB_PASSWORD:?…}`, `pg_isready -h 127.0.0.1` |
| V-13 | `server.tomcat.max-part-count` | Boot 기본이 10이면 첨부 9개 넘는 문의 등록이 500일 수 있다(추측) | 테스트로 먼저 확인. 맞으면 상한을 정해 413 |
| V-15 | `PasswordConfiguration`, `ResetPasswordCommand` | Argon2 메모리 16MiB(Spring Security 기본)가 OWASP 최소보다 작다 | 19MiB·반복 2·병렬 1로, 두 곳이 한 곳에서 만든다(7절, 사람이 정함) |
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

- 이번에 한다: V-1~V-3(끝남), V-4, V-5, V-6, V-12, V-15, 웹 변이 테스트 결과 합치기, 보안 검사 도구, 핵심 흐름 브라우저 확인.
- 0.1.0 뒤, 1번 묶음 앞에 한다: V-7~V-11, V-13, V-14, 서버 PIT. 드물거나 로그 양 정도의 문제다. PIT는 Testcontainers 테스트라 웹보다 오래 걸려 릴리스 태그 뒤에 따로 돈다.

## 7. 사람이 정한 것 (2026-10-10)

- Argon2 매개변수: 메모리 19MiB, 반복 2, 병렬 1로 올린다(V-15). features.md AUTH-5를 고쳤다. 해시 문자열에 매개변수가 들어 있어 있던 해시도 맞는다.
- 믿는 프록시 기본값(ADR D-46): 기본값은 둔다. configuration.md TLS 절과 threat-model TM-28에 프록시 없는 사내망·Docker Desktop의 위험과 값을 좁히는 법을 적었다.
