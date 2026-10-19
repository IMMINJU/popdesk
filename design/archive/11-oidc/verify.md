# 11 묶음(OIDC 로그인) 검증 (C-10)

> 2026-10-19 메인이 이 PC에서 했다. GitHub Actions 10월분을 다 써서 CI는 돌지 않았다.
> C-10은 닿은 부분만 돈다(`process.md` C-10 첫 줄).
> 숫자는 각 실행의 로그(`C:\Users\minju\.claude\popdesk\tasks\s11-ci\`)와 탭 보고에서 옮겼다.

## 1. 구현 탭 둘이 끝났고 맡은 테스트는 모두 통과한다

| 탭 | 맡은 것 | 결과 |
|---|---|---|
| `s11-i-server` | `pending.txt` 서버 클래스 25, deploy `OidcSampleTest` | 모두 통과, 견본 테스트와 `UpgradeHarnessTest` 7 통과. 같이 돈 auth·start 416, ops·audit 278, privacy·project·boundary와 구조 검사 402에서 실패 0. 계약 차이 없음 |
| `s11-i-web` | `pending.txt` 웹 줄과 story | console 1,487을 포함한 패키지 테스트 전체, story 실행기 737, typecheck, lint, build, 포털 번들 검사 통과. dup 5.57% → 5.53%. 공용 컴포넌트 API는 그대로 |

- 잠긴 테스트가 틀린 것은 하나였다. `OidcMatchTest#everyNoMatchLooksTheSame[CANCELLED_INVITATION]`가 초대 취소에 200을 기다렸는데 계약은 204다. 메인이 고치고 다시 잠갔다(`84e0774`).
- 웹 탭은 `package.json`이 잠겨 새 내보내기 경로를 더하지 못해 `@popdesk/app/bands`·`hooks`로 다시 내보냈다. 메인이 그대로 두기로 했다.
- 합친 뒤 main의 서버·배포 코드는 서버 탭이 돌린 트리와 같다. 닿은 서버 패키지는 탭 결과로 갈음했다.

## 2. 이 PC에서 돈 것 (닿은 부분만)

| 무엇 | 결과 | 시간 |
|---|---|---|
| 관문: `tests-lock.py check`, `check-locked-paths.py --role impl` | 통과(잠금 1,050, 바꾼 경로 25) | 수 초 |
| `popdesk:test` 이미지 빌드 | 통과 | 2분 1초 |
| 숨긴 E2E `OidcFlowTest` | 13 통과(네 번째 실행) | 1분 21초 |
| 순수 계산 단위 테스트 `SafeNextTest`, `OidcSettingsTest`, `IdTokenClaimsTest` | 135 통과 | 46초 |

숨긴 E2E는 세 번 실패한 뒤 지났다. 두 번은 이 PC의 사정(같은 때 돈 이미지 빌드, 웹 탭의 설치가 쥔 8080)이었다. 한 번은 숨긴 테스트의 기대가 기준 문서와 어긋난 것이라 메인이 숨긴 쪽에서 고쳤다. 운영 코드는 고치지 않았다.

## 3. 변이 테스트

| 대상 | 만든 변이 | 잡음 | 살아남음 |
|---|---|---|---|
| 서버 PIT `SafeNext` | 15 | 15 | 0 |
| 서버 PIT `OidcSettings` | 53 | 52 | 1 |
| 서버 PIT `IdTokenClaims` | 69 | 66 | 3 |

- 단위 테스트는 테스트 쪽 서브에이전트가 acceptance와 conventions를 기준으로 썼다(`f3c30f3`, 잠금 `ec45ddc`). 첫 PIT에서 살아남은 `OidcSettings.read`의 키 둘만 있는 경우를 S11-AC-01로 테스트를 더해 잡았다.
- 살아남은 넷: `audiences`의 null을 빈 목록으로 바꾼 것(어느 쪽이든 `AUDIENCE` 실패), `sameEmail`의 `> 0x7f`를 `>=`로 바꾼 둘(DEL은 글자가 아니라 결과가 같다), `OidcSettings.toString`(기준이 담을 값을 정하지 않았다. 비밀값이 없다는 것은 테스트가 본다).
- 기준에는 없고 클래스 주석에만 있는 것 셋: `nbf`도 60초 앞까지 받음, `next` 512자는 코드포인트로 셈, `OIDC_ISSUER` 앞뒤 공백을 자름. 셋 다 테스트가 지난다.
- 웹에는 새 순수 함수가 없어 Stryker는 돌지 않았다.

## 4. 메인이 코드로 본 것

테스트가 약해 C-10에서 코드로 보기로 한 셋(`acceptance.md` 5절 18)이다.

- 잠그기 전 읽은 이메일: `OidcSignInService.match`는 잠그지 않고 `findByEmail`로 찾은 뒤, 수락 전이면 `lockInvitation`(초대 수락과 같은 잠금 차례), 그다음 `lockById`로 계정을 잠그고 잠근 행의 이메일을 토큰 이메일과 다시 비교한다. 상태, 역할, 초대 기한, 다른 `sub`도 잠근 행으로 본다. 묶음 넣기는 `ON CONFLICT DO NOTHING`이고, 못 넣으면 다시 찾아 같은 계정일 때만 받는다. 실패는 `NoMatch` 예외로 트랜잭션을 되돌린다(`TransactionTemplate`).
- 요청 지우기의 한 번만: `OidcRequestRepository.take`가 `DELETE … RETURNING` 한 문장이다. 쿠키, 앱, 10분은 지운 뒤에 보고, 제공자의 `error`도 그 뒤에 본다.
- 토큰 시각의 시계: `OidcProvider`가 Nimbus의 클레임 검사기와 Spring의 JWT 검증기를 비워 서명만 보게 했다. 시각은 `IdTokenClaims.check(…, clock.instant())`가 60초 허용으로 본다. 발견 문서 1시간, 실패 30초, JWKS 1시간도 주입한 `Clock`이다.

## 5. 브라우저

웹 탭(`popdesk:s11-i-web`, 시험 제공자 `mock-oauth2-server`를 `oidc.localhost:9090`으로, 콘솔 1280·820px, 포털 1280·360px, 밝게·어둡게):
- 로그인 단추와 띠 셋, 실제 OIDC 로그인으로 처음 묶기(콘솔과 포털, `next` 그대로)
- 실제 서버가 낸 `failed`·`unavailable` 띠
- C-10 배지와 연결 풀기(풀린 계정의 다른 탭이 401)
- C-18 켜짐과 실패 1건, C-12 새 종류 다섯 줄
- 가로 넘침 없음, 콘솔 오류는 로그인 전 401뿐
- 제공자 주소가 지시서와 다른 까닭: 브라우저가 compose 서비스 이름을 풀지 못하고, 8080은 앱이 쓴다. Chrome은 `*.localhost`를 hosts 파일 없이 풀고 컨테이너 안에서는 네트워크 별칭이 풀린다.

메인(`popdesk:test`, OIDC 키 없이, Chrome DevTools MCP)은 웹 탭이 못 본 꺼짐 상태를 봤다.

| 본 것 | 폭과 테마 | 결과 |
|---|---|---|
| C-1 로그인 | 1280 밝게 | 회사 계정 단추와 "또는" 선이 없다 |
| P-1 로그인 | 820 어둡게 | 단추 없음. `sign-in-options`는 `{"oidc":false}`, `/api/public/oidc/start`는 404 |
| C-18 OIDC 구역 | 1280 밝게, 820 어둡게 | 채널 표 바로 뒤에 "OIDC 로그인"과 `꺼짐` 배지만. 가로 넘침 없음 |

- 콘솔 오류: 로그인 전 401 둘과 메인이 일부러 부른 시작의 404 하나.
- 스크린샷: `C:\Users\minju\.claude\popdesk\shots\s11-main\`, 웹 탭 것은 `C:\Users\minju\.claude\popdesk\tasks\shots\s11-i-web\`. 설치는 `down -v`로 지웠다.
- 아무도 화면으로 보지 못한 것: 연결 풀기의 404·그 밖의 실패와 늦은 답, 단추의 "이동 중…"과 뒤로 가기(`pageshow`)로 되돌아옴, 비밀번호 로그인 시도로 띠가 지워짐. 모두 잠긴 웹 테스트가 본다.

## 6. 정한 것 (메인이 정함, 사용자 확인 전)

| 무엇 | 정한 것 | 까닭 |
|---|---|---|
| 잠긴 `OidcMatchTest` 초대 취소 기대값 | 200을 204로 고침 | 계약이 204 |
| 웹 공용 조각의 내보내기 경로 | `@popdesk/app/bands`·`hooks`로 다시 내보냄 | `package.json`이 잠겨 있음. 8a `slaWords`와 같은 꼴 |
| 820px C-12 일 칸이 세 줄로 접힘 | 이번에는 두고 넘김 | 낱말 사이에서 접히고, 앞 묶음부터 있던 칸 폭 |

## 7. 릴리스 노트에 넣을 것

- OIDC 로그인(AUTH-6). 설정 키 `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`(`_FILE`), `OIDC_APPS`. 키 일부만 있거나 `OIDC_APPS`·`OIDC_ISSUER` 꼴이 틀리면 업그레이드 뒤 뜨지 않는다. 제공자에 돌아오기 주소 `/api/public/oidc/callback`을 앱마다 등록한다. 비밀번호 로그인과 비밀번호 찾기는 그대로 남는다(D-65).
- ID 토큰에 `email`과 `email_verified`가 있어야 한다. Microsoft Entra ID는 넣지 않는다.
- 새 경로 넷, 계정 응답 `oidcLinked`, 시스템 상태 `oidc`, 감사 종류 `OIDC_LINKED`·`OIDC_UNLINKED`·`INVITATION_ACCEPTED`(OIDC 수락만), 로그인 감사의 `{method: "OIDC"}`.
- 익명화가 OIDC 묶음을 지운다. 마이그레이션 V24(표 둘을 더함).

## 8. 넘긴 일

- 콘솔 C-12 감사 로그 표의 "일"·"대상" 칸이 820px에서 좁다. "로그인 (회사 계정)", "OIDC 연결 풀기"가 세 줄, "계정 정지"와 "계정 6"이 두 줄로 접힌다.
- 모르는 `kid`로 JWKS를 다시 받는 데 간격 제한이 없다. 토큰은 제공자의 토큰 주소에서만 오고 시작이 IP당 제한되어 있어 두었다.
