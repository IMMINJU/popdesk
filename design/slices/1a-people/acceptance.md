# 1a 묶음 수용 기준: 사람과 고객사

> `roadmap.md` 6절 1a번의 EARS 수용 기준이다. 묶음이 끝나면 `design/archive/1a-people/`로 옮긴다(D-27).
> 전체 경로다. 마이그레이션, 고객사 경계, 개인정보 흐름(프로필 사진, 비밀번호 재설정 메일), 공개 계약(API)을 건드린다. 사전 부검은 같은 폴더의 `premortem.md`에 쓴다(C-3).
> 꼴은 첫 묶음과 같다(`When`, `While`, `Where`, `If … then`, `The system shall`). 괄호 안은 근거다. 규칙 ID의 글은 같은 폴더 `rules.md`에 있다(`spec/features.md` 1~3절을 옮긴 것). 기준 ID는 `S1A-AC-nn`이다.
> 관계있는 결정(`adr.md`): D-18, D-20, D-21, D-24, D-43, D-44, D-49, D-52, D-53.
> 기준 한 줄에는 밖에서 보이는 동작 하나만 적는다. 어떻게 만드는지(잠금 순서, 쓸 클래스)는 적지 않고 설계(C-2)와 `architecture.md`에 둔다.
> 상태: 사용자가 확인했다(2026-10-11). 1절의 다섯 가지를 정했고 5절의 여덟 가지는 그대로 두기로 했다. 3.13절(사전 부검에서 더한 것)도 확인했다.

## 1. 사용자가 정한 것 (2026-10-11)

1. 1번 묶음을 둘로 나눈다. 1a는 사람과 고객사(AUTH, ACCT, INVITE, PROJ), 1b는 감사 로그(AUDIT 보기·내보내기·해시 사슬·무결성 확인·바깥 사본)다. 이 묶음에서 생기는 일을 감사 로그에 남기는 것은 1a에 넣는다(3.10절).
2. 담당 비우기(ACCT-7)는 3번 묶음으로 미룬다. 담당자가 3번에서 생긴다. 1a의 정지와 배정 빼기는 담당 수를 보이지 않는다.
3. 익명화에 따른 감사 사슬 재계산은 10번 묶음으로 미룬다. 1b는 사슬 계산과 무결성 확인까지 한다.
4. 문의 등록 한 번에 붙이는 파일은 20개까지다. 넘으면 413이다.
5. 문의 등록에 붙인 파일도 첨부 횟수 제한(S0-AC-65)에 센다.

## 2. 넣는 것과 미루는 것

| 기능 | 1a에 넣는 것 | 미루는 것 | 묶음 |
|---|---|---|---|
| AUTH-4 | 정지, 비밀번호 변경·초기화·찾기, 역할 변경 때 그 계정의 세션 지우기 | 익명화 때 세션 지우기 | 10 |
| AUTH-7 | 비밀번호 찾기 전부 | – | – |
| AUTH-6 | – | OIDC | 11 |
| ACCT-2 | 본인의 이름, 언어, 비밀번호 바꾸기 | – | – |
| ACCT-3 | 계정 목록의 필터·검색, 이름 고치기, 역할 바꾸기, 정지·해제, 비밀번호 초기화 | 정보 내보내기, 익명화 | 10 |
| ACCT-4 | 고객이 보는 사람 이름과 사진의 범위 | – | – |
| ACCT-7 | – | 담당 수 보이기, 담당 비우기 | 3 |
| ACCT-8 | 프로필 사진 전부(D-52) | 익명화 때 지우기, 내보내기에 넣기 | 10 |
| INVITE-3 | 다시 보내기, 취소 | 고객사 담당자의 다시 보내기·취소 | 2 |
| PROJ-1~4 | 고치기, 상태 바꾸기, `ACTIVE`가 아닌 고객사의 제한, 소속 더하기·빼기, 배정 빼기 | 고객사 담당자 지정, 소속 요청 | 2 |
| AUDIT-1·2 | 이 묶음에서 생기는 일 남기기 | 보기, 내보내기, 해시 사슬, 무결성 확인, 바깥 사본, 보관 기간 | 1b |
| 첫 묶음에서 넘어온 것 | 3.11절 | 웹훅 3xx와 연결 실패 경로, 메일의 보낸 사람 주소(`MAIL_FROM`) | 4 |
| | | 내려받기 `Content-Type` | 5 |

## 3. 수용 기준

### 3.1 세션 끊기

| ID | 기준 |
|---|---|
| S1A-AC-01 | When 관리자가 계정을 정지하면, the system shall 그 계정의 세션을 모두 지운다. 그 계정의 다음 요청은 401이다. (AUTH-4, ACCT-3) |
| S1A-AC-02 | When 본인이 비밀번호를 바꾸면, the system shall 그 계정의 다른 세션을 모두 지우고 지금 세션은 새 세션 ID로 잇는다. (AUTH-4, ACCT-2) |
| S1A-AC-03 | When 관리자가 계정의 역할을 바꾸면, the system shall 그 계정의 세션을 모두 지운다. (AUTH-4, ACCT-3) |
| S1A-AC-04 | When 비밀번호가 초기화되거나 비밀번호 찾기로 바뀌면, the system shall 그 계정의 세션을 모두 지운다. (AUTH-4, AUTH-7) |

### 3.2 비밀번호 찾기

| ID | 기준 |
|---|---|
| S1A-AC-05 | When 로그인 화면에서 이메일을 넣어 비밀번호 찾기를 요청하면, the system shall 계정이 있든 없든, 정지·수락 전이든, 반대쪽 앱의 계정이든 같은 202 본문으로 답한다. (AUTH-7, TM-10) |
| S1A-AC-06 | When 요청한 이메일이 그 앱에 로그인할 수 있는 `ACTIVE` 계정이면, the system shall 그 앱 주소의 재설정 링크를 담은 메일을 보내기 대기에 넣는다. 메일은 계정의 언어로 쓴다. (AUTH-7, MAIL-1) |
| S1A-AC-07 | If 요청한 이메일이 `ACTIVE` 계정이 아니거나 반대쪽 앱의 계정이면, then the system shall 메일을 넣지 않는다. (AUTH-7) |
| S1A-AC-08 | The system shall 재설정 토큰을 32바이트 난수로 만들고 DB에는 SHA-256 해시만 둔다. 토큰은 링크의 URL 조각(`#` 뒤)에만 둔다. (AUTH-7, INVITE-2, TM-11) |
| S1A-AC-09 | When 링크를 연 사람이 AUTH-5에 맞는 새 비밀번호를 정하면, the system shall 비밀번호를 바꾸고 잠금을 푼다. 로그인시키지는 않는다. (AUTH-7, AUTH-3) |
| S1A-AC-10 | If 토큰이 없거나, 1시간이 지났거나, 이미 썼거나, 새 요청으로 무효가 됐거나, 그 계정의 앱이 아닌 호스트로 왔으면, then the system shall 모두 같은 400으로 답하고 비밀번호를 바꾸지 않는다. 요청에 `token` 칸이 없거나 비어 있으면 무효인 토큰이 아니라 400 `INVALID_INPUT`(`token` `REQUIRED`)이다. (AUTH-7, C-5에서 정함) |
| S1A-AC-11 | If 새 비밀번호가 AUTH-5에 맞지 않아 400이 나면, then the system shall 토큰을 쓰지 않은 것으로 둔다. (AUTH-7, S0-AC-36과 같게) |
| S1A-AC-12 | When 같은 계정에 비밀번호 찾기를 다시 요청하면, the system shall 이전 링크를 무효로 한다. If 앞 메일을 넣은 지 15분이 안 됐으면, then 새 메일을 넣지 않고 이전 링크도 그대로 둔다. (AUTH-7) |
| S1A-AC-13 | If 한 IP에서 60초 안에 비밀번호 찾기 요청이 20번을 넘으면, then the system shall 429와 `Retry-After`로 답한다. (AUTH-7, COM-8) |
| S1A-AC-14 | The system shall 재설정 토큰을 담거나 받는 응답에 `Cache-Control: no-store`를 붙이고, 토큰과 링크를 로그와 감사 로그에 남기지 않는다. (AUTH-7, PII-2, S0-AC-101) |
| S1A-AC-15 | The system shall 쓰거나 만료된 재설정 토큰을 정리 작업으로 지우고, 그 작업의 마지막 실행 시각과 지운 수를 남긴다. 이 정리는 다른 정리 작업에 얹지 않고 자기 기록 행을 따로 갖는다. (features 13절 보관 표, OPS-2) |

### 3.3 내 정보

| ID | 기준 |
|---|---|
| S1A-AC-16 | When 본인이 이름(1~50자)이나 언어(`ko`·`en`)를 바꾸면, the system shall 저장하고 다음 응답부터 새 값을 준다. 그 뒤 그 계정에 가는 메일은 새 언어다. (ACCT-1, ACCT-2, MAIL-1) |
| S1A-AC-17 | When 본인이 지금 비밀번호와 AUTH-5에 맞는 새 비밀번호를 내면, the system shall 비밀번호를 바꾼다. (ACCT-2) |
| S1A-AC-18 | If 지금 비밀번호가 틀리면, then the system shall 400 `CURRENT_PASSWORD_WRONG`으로 답하고 바꾸지 않는다. 다섯 번 연속 틀리면 로그인 잠금(S0-AC-23)과 같이 15분 동안 이 요청을 429와 `Retry-After`로 막는다(다섯째까지는 400이고 여섯째부터 429다. 막힌 동안 그 세션은 이어진다). (ACCT-2, AUTH-3, 5절 1) |
| S1A-AC-19 | The system shall 내 정보 응답에 이름, 이메일, 언어, 역할, 사진을 바꾼 시각(없으면 `null`)을 주고, 고객에게는 소속 고객사를, 상담원에게는 배정된 고객사를 함께 준다. (screens P-6, C-6) |

### 3.4 프로필 사진

| ID | 기준 |
|---|---|
| S1A-AC-20 | When 본인이 2MB 이하의 PNG·JPEG·WebP 사진을 올리면, the system shall 가로세로 256px 안에 들게 줄여 다시 인코딩한 것만 저장한다. 다시 올리면 앞 사진을 바꾼다. (ACCT-8, D-52) |
| S1A-AC-21 | The system shall 저장한 사진에 원본의 메타데이터(EXIF 위치 등)를 남기지 않고, 원본 바이트를 저장하지 않는다. (ACCT-8, D-52, PII-1) |
| S1A-AC-22 | If 파일이 2MB를 넘으면, then the system shall 413으로 답한다. If 세 형식이 아니거나 디코딩되지 않으면, then 400 `UNSUPPORTED_IMAGE`다. 판정은 파일 이름이나 `Content-Type`이 아니라 내용으로 한다. (ACCT-8) |
| S1A-AC-23 | If 사진의 가로×세로가 PNG·JPEG는 2,500만 픽셀, WebP는 1,200만 픽셀을 넘으면, then the system shall 디코딩하기 전에 400 `IMAGE_TOO_LARGE`로 답한다. (5절 2, S1A 부검 4, 실험) |
| S1A-AC-24 | When 본인이 사진을 지우면, the system shall 저장한 사진을 지운다. 사진이 없는 사람은 응답에서 사진 없음으로 나온다. (ACCT-8) |
| S1A-AC-25 | The system shall 고객에게 자기 자신과 같은 고객사 고객의 사진만 준다. 그 밖의 사진 요청은 없는 계정과 같은 404다. (ACCT-4, RULE-01) |
| S1A-AC-26 | The system shall 포털 응답 어디에도 상담 쪽 사람의 사진이나 사진 주소를 넣지 않는다. 고객이 상담 쪽 사람의 사진을 요청하면 404다. (ANON-1, D-49, S0-AC-98) |
| S1A-AC-27 | The system shall 상담원과 관리자에게 볼 수 있는 계정의 사진을 준다. 상담원이 배정되지 않은 고객사에만 속한 고객의 사진은 404다. (ACCT-4, RULE-03) |
| S1A-AC-28 | If 한 계정이 60초 안에 사진을 10번 넘게 올리면, then the system shall 429와 `Retry-After`로 답한다. (COM-8, 5절 2) |

### 3.5 구성원 관리

| ID | 기준 |
|---|---|
| S1A-AC-29 | When 관리자가 계정 목록을 요청하면, the system shall 역할과 상태로 거르고 이름·이메일의 일부로 찾은 결과를 COM-4로 쪽 나눠 준다. 상담원에게는 403이다. (ACCT-3) |
| S1A-AC-30 | When 관리자가 계정의 이름을 고치면, the system shall 저장한다. 수락 전 계정도 된다. (ACCT-3, INVITE-5) |
| S1A-AC-31 | When 관리자가 `AGENT`를 `ADMIN`으로, `ADMIN`을 `AGENT`로 바꾸면, the system shall 역할을 바꾼다. 상담원 배정은 지우지 않고 둔다. (ACCT-3, 5절 3) |
| S1A-AC-32 | If 고객 역할로 바꾸거나 고객의 역할을 바꾸려 하면, then the system shall 400으로 답한다. (ACCT-3) |
| S1A-AC-33 | If 관리자가 자기 자신을 정지하거나 자기 역할을 바꾸려 하면, then the system shall 400으로 답한다. (ACCT-3) |
| S1A-AC-34 | When 관리자가 `ACTIVE` 계정을 정지하면, the system shall 상태를 `SUSPENDED`로 바꾼다. 정지된 계정은 로그인할 수 없고 메일을 받지 않는다. 이미 정지된 계정을 다시 정지하면 200이다. If 수락 전이거나 초대가 취소된 계정을 정지하려 하면, then 400 `ACCOUNT_NOT_ACTIVE`다(초대 취소를 쓴다). (ACCT-3, S0-AC-22, S0-AC-69, C-4에서 정함) |
| S1A-AC-35 | When 관리자가 정지를 풀면, the system shall 상태를 `ACTIVE`로 바꾼다. 이미 `ACTIVE`면 200이다. If 그 계정이 초대를 수락한 적이 없으면, then 400이다(다시 보내기를 쓴다). (ACCT-3, INVITE-3, 5절 4) |
| S1A-AC-36 | When 관리자가 `ACTIVE` 계정의 비밀번호를 초기화하면, the system shall 지금 비밀번호를 못 쓰게 하고, 잠금을 풀고, 비밀번호 찾기와 같은 재설정 메일을 넣는다. If 계정이 `ACTIVE`가 아니면, then 400 `ACCOUNT_NOT_ACTIVE`다. (ACCT-3, AUTH-3, AUTH-7, 5절 5, C-4에서 정함) |
| S1A-AC-37 | Where SMTP 설정이 없으면, the system shall 비밀번호 초기화 응답에 재설정 링크를 한 번만 준다(`Cache-Control: no-store`). SMTP 설정이 있으면 응답에 링크가 없다. 초기화를 다시 하면 비밀번호 찾기의 15분 규칙과 관계없이 새 링크를 만들고 앞 링크를 무효로 한다. (INVITE-6과 같게, 5절 5, C-5에서 정함) |

### 3.6 초대 다시 보내기와 취소

| ID | 기준 |
|---|---|
| S1A-AC-38 | When 관리자가 수락 전 계정에 초대를 다시 보내면, the system shall 새 토큰을 만들어 이전 링크를 무효로 하고 초대 메일을 넣는다. 만료는 그때부터 7일이다. SMTP 설정이 없으면 초대할 때처럼 링크를 응답에 한 번 준다. (INVITE-3, INVITE-2, INVITE-6, C-4에서 정함) |
| S1A-AC-39 | When 관리자가 수락 전 계정의 초대를 취소하면, the system shall 계정을 `SUSPENDED`로 바꾸고 링크를 무효로 한다. 대기에 남은 초대 메일은 나가지 않는다. (INVITE-3, S0-AC-69) |
| S1A-AC-40 | When 취소된 초대를 다시 보내면, the system shall 계정을 `INVITED`로 되돌리고 새 초대 메일을 넣는다. (INVITE-3) |
| S1A-AC-41 | If 수락한 적이 있는 계정에 다시 보내기나 취소를 하려 하면, then the system shall 400으로 답한다. (INVITE-3) |
| S1A-AC-42 | The system shall 수락 전 계정 목록(S0-AC-40)에 취소된 초대도 취소됨 표시와 함께 준다. (INVITE-3, screens C-10) |

### 3.7 고객사 고치기와 상태

| ID | 기준 |
|---|---|
| S1A-AC-43 | When 관리자가 고객사의 이름이나 회사명을 고치면, the system shall 저장한다. 코드는 고칠 수 없다(요청에 코드가 있으면 400). (PROJ-1) |
| S1A-AC-44 | When 관리자가 고객사 상태를 `ACTIVE`↔`SUSPENDED`로, 또는 둘 가운데 하나에서 `ARCHIVED`로 바꾸면, the system shall 상태를 바꾼다. 지금과 같은 상태로 바꾸는 요청은 200이다. (PROJ-2) |
| S1A-AC-45 | If `ARCHIVED` 고객사의 상태를 바꾸려 하면, then the system shall 400 `COMPANY_ARCHIVED`로 답한다. 같은 `ARCHIVED`로 바꾸는 요청도 400이다. (PROJ-2) |
| S1A-AC-46 | If `ACTIVE`가 아닌 고객사에 새 문의를 올리면, then the system shall 400 `COMPANY_NOT_ACTIVE`로 답한다. (PROJ-3) |
| S1A-AC-47 | While 고객사가 `ACTIVE`가 아니어도, the system shall 이미 있는 문의의 보기, 댓글, 첨부를 그대로 받는다. (PROJ-3) |
| S1A-AC-48 | If `ARCHIVED` 고객사에 소속이나 배정을 더하거나 이름·회사명을 고치려 하면, then the system shall 400으로 답한다. 빼기는 된다. (PROJ-3) |
| S1A-AC-49 | The system shall 고객사 목록과 상세 응답에 상태를 준다. 고객의 고객사 목록에는 소속된 곳이 상태와 관계없이 나온다. (PROJ-5, screens P-4) |

### 3.8 소속과 배정

| ID | 기준 |
|---|---|
| S1A-AC-50 | When 관리자가 고객 계정(수락 전 포함)을 고객사에 소속시키면, the system shall 다음 요청부터 그 고객에게 그 고객사와 그 공개 문의를 보인다. 같은 소속을 다시 하면 200이다. (PROJ-4, S0-AC-26) |
| S1A-AC-51 | If 고객이 아닌 계정을 소속시키거나 상담원이 아닌 계정을 배정하려 하면, then the system shall 400으로 답한다. (PROJ-4, 5절 6) |
| S1A-AC-52 | When 관리자가 고객을 소속에서 빼면, the system shall 다음 요청부터 그 고객에게 그 고객사와 동료의 문의를 보이지 않는다(404, 목록에 없음). 이미 뺀 것을 다시 빼면 200이다. 빠진 고객이 그 고객사에 새 문의를 올리면 다른 고객사에 올릴 때와 같은 404다. (PROJ-4, RULE-02) |
| S1A-AC-53 | While 고객이 소속에서 빠져 있어도, the system shall 그 고객이 올린 문의의 상세, 댓글 목록, 첨부 내려받기를 그대로 준다. (PROJ-4, 권한 모델) |
| S1A-AC-54 | If 소속에서 빠진 고객이 자기가 올린 문의에 댓글을 달거나 첨부를 붙이거나 지우려 하면, then the system shall 403으로 답한다. (PROJ-4) |
| S1A-AC-55 | When 관리자가 상담원을 배정에서 빼면, the system shall 다음 요청부터 그 상담원에게 그 고객사의 문의, 댓글, 첨부를 404로 답하고 목록에서 뺀다. 이미 뺀 것을 다시 빼면 200이다. (PROJ-4, RULE-03) |
| S1A-AC-56 | If 상담원이 소속이나 배정을 더하거나 빼려 하면, then the system shall 403으로 답한다. (PROJ-4, S0-AC-30) |

### 3.9 화면

화면 상태 목록은 같은 폴더 `screens.md`에 쓴다(P-6, P-9, P-10, C-6, C-16, C-17과 C-8·C-9·C-10의 바뀌는 곳). 아래는 화면에만 있는 기준이다.

| ID | 기준 |
|---|---|
| S1A-AC-57 | When 화면이 403 `CSRF_TOKEN_INVALID`를 받으면, the system shall 토큰을 새로 받아 그 요청을 한 번만 다시 보낸다. 또 실패하면 "권한이 없습니다"가 아니라 새로고침하라는 안내를 보인다. (verify 6절, UX-5) |
| S1A-AC-58 | The system shall 이 묶음에서 요청을 보내는 화면과 대화상자마다, 닫히거나 대상이 바뀐 뒤에 온 답을 버린다. (process C-11 규칙, V-2) |
| S1A-AC-59 | When 비밀번호 찾기 화면에서 이메일을 내면, the system shall 계정이 있든 없든 같은 문구("메일을 보냈다면 1시간 안에 링크를 쓰라")를 보인다. (AUTH-7, screens P-9) |
| S1A-AC-60 | The system shall 사진이 없는 사람을 이름 머리글자로 보인다. 포털은 상담 쪽 사람을 사진 없이 "지원팀"으로 보인다. (ACCT-8, ANON-1) |
| S1A-AC-61 | The system shall 해시가 붙은 화면 파일(`assets/**`)에 `Cache-Control: public, max-age=31536000, immutable`을, `index.html`에 `no-cache`를 붙인다. (verify 6절) |

### 3.10 감사 로그에 남기기

| ID | 기준 |
|---|---|
| S1A-AC-62 | When 초대 다시 보내기·취소, 정지·해제, 역할·이름 변경(관리자가 한 것), 비밀번호 초기화, 비밀번호 찾기로 바꾼 비밀번호, 본인의 비밀번호 변경, 고객사 고치기·상태 변경, 소속·배정 더하기·빼기가 일어나면, the system shall S0-AC-83의 모양으로 감사 행을 남긴다. 비밀번호 찾기 요청은 남기지 않는다. (AUDIT-1, AUDIT-2, 5절 7) |
| S1A-AC-63 | The system shall 감사 행의 바뀐 값에 전과 뒤를 넣되 비밀번호, 토큰, 링크, 사진 내용은 넣지 않는다. (AUDIT-2, PII-2) |
| S1A-AC-64 | The system shall 로그인 성공·실패·잠금 감사 행의 대상을 그 계정으로 적는다. 계정이 없는 이메일의 실패는 첫 묶음대로 행을 남기지 않는다(입력한 이메일을 저장하지 않는다). (S0-AC-83, PIT 질문 8) |

### 3.11 첫 묶음에서 넘어온 것

| ID | 기준 |
|---|---|
| S1A-AC-65 | If 문의 등록 한 번에 파일이 20개를 넘으면, then the system shall 413으로 답하고 문의를 만들지 않는다. 20개는 받는다. (1절 4, V-13) |
| S1A-AC-66 | The system shall 문의 등록에 붙인 파일도 한 개마다 첨부 횟수 제한(60초 30건, S0-AC-65)에 센다. (1절 5) |
| S1A-AC-67 | If 글 입력에 NUL(U+0000)이나 짝 없는 서로게이트가 있으면, then the system shall 400 `INVALID_INPUT`과 그 칸의 `INVALID_FORMAT`으로 답한다. (verify 6절, conventions "Text input") |
| S1A-AC-68 | When 첨부 합이 한도의 80% 아래로 내려가면(지우기든 정리 작업이든), the system shall 첨부 경보를 푼다. When 보내기 대기의 `failed` 행이 하나도 없게 되면, 실패 경보를 푼다. 풀린 뒤 다시 생기면 그날 이미 한 건을 남겼어도 새 항목을 남긴다. (S0-AC-80, PIT 질문 1) |
| S1A-AC-69 | When 로그인하면, the system shall CSRF 토큰을 새로 낸다. 로그인 전에 받은 토큰으로 보낸 쓰기 요청은 403이다. (S0-AC-17, PIT 질문 4) |
| S1A-AC-70 | The system shall 첫 관리자 값이 없을 때의 503 응답에 `Content-Type: application/json`을 붙인다. (S0-AC-02, PIT 질문 5) |
| S1A-AC-71 | The system shall 429의 `Retry-After`를 그 창이 끝날 때까지 남은 초(올림)로 준다. 그 초가 지난 뒤 보낸 요청은 429가 아니다. (COM-8, PIT 질문 6) |
| S1A-AC-72 | If 초대하는 이메일이 100자를 넘으면, then the system shall 400과 그 칸의 `TOO_LONG`으로 답한다. 100자는 받는다. (ACCT-1, PIT 질문 7) |
| S1A-AC-73 | The system shall 믿는 프록시 대역의 접두어가 8비트 단위가 아닐 때(`172.16.0.0/12`)도 대역 안팎을 맞게 가른다. (S0-AC-18, PIT E) |
| S1A-AC-74 | If 요청 본문이 JSON이 아니면, then the system shall 400 `INVALID_INPUT`을 `fields` 없이 준다. If 칸의 타입이 맞지 않으면, then 그 칸의 `INVALID_FORMAT`을, 정해진 값 밖이면 `UNKNOWN_VALUE`를 준다. (COM-3, D-44, PIT E) |

첨부 크기의 1MB는 1,048,576바이트다(S1A-AC-75였던 것). 첫 묶음의 잠긴 `AttachmentLimitsTest`와 `FilingAttachmentLimitTest`가 이미 그 경계를 봐서 새 기준으로 두지 않고, `conventions.md`와 화면 문구에 같은 뜻으로 적는다.

### 3.12 함께 붙일 관문

| ID | 기준 |
|---|---|
| S1A-AC-76 | 고객사 경계 부정 테스트. A사·B사 픽스처로, the system shall 이 묶음의 모든 새 엔드포인트에서 A사 고객에게 B사 고객의 이름, 이메일, 사진을 주지 않는다. (RULE-01, TM-01) |
| S1A-AC-77 | 이 묶음의 모든 엔드포인트는 경계 테스트 목록에 있다. 경계와 상관없는 것(비밀번호 찾기, 내 정보)은 이유와 함께 올린다. (S0-AC-93) |
| S1A-AC-78 | The system shall 이 묶음의 메일(비밀번호 재설정)과 응답 어디에도 다른 계정이 있는지를 드러내지 않는다. 응답 본문과 상태 코드가 계정 유무와 관계없이 같은지 보는 테스트를 둔다. (AUTH-2, AUTH-7, TM-10) |
| S1A-AC-80 | 업그레이드 테스트. When v0.1.0 견본 덤프를 이 묶음의 이미지로 올리면, the system shall 마이그레이션이 끝나고 견본 데이터 확인이 통과한다. 처음으로 실제 마이그레이션이 도는 묶음이다. (S0-AC-95, D-24) |

계약 파일 둘과 클라이언트 둘이 커밋된 것과 같아야 한다는 것(S1A-AC-79였던 것)은 첫 묶음의 관문(S0-AC-91, 잠긴 `OpenApiFilesTest`와 `deploy.ContractClientsTest`)이 그대로 본다. 새 기준으로 두지 않는다.

### 3.13 사전 부검에서 더한 것

같은 폴더 `premortem.md`의 장치다. 괄호의 `S1A 부검 n`은 그 파일의 절이다.

| ID | 기준 |
|---|---|
| S1A-AC-81 | If 정지나 역할 변경으로 `ACTIVE` 관리자가 하나도 남지 않게 되면, then the system shall 400 `LAST_ADMIN`으로 답하고 바꾸지 않는다. 두 관리자가 서로를 같은 때 내려도 하나는 실패한다. (S1A 부검 1) |
| S1A-AC-82 | The system shall 재설정 링크의 주소를 설정한 공개 주소로 만든다. 요청의 `Host`나 `X-Forwarded-Host` 값은 링크에 쓰지 않는다. (S1A 부검 2, S0-AC-20) |
| S1A-AC-83 | If 초대 토큰을 재설정 API에, 재설정 토큰을 초대 수락 API에 내면, then the system shall 무효인 토큰과 같은 400으로 답한다. (S1A 부검 2) |
| S1A-AC-84 | If 재설정 링크를 받은 뒤 계정이 정지되면, then the system shall 그 링크를 무효인 토큰과 같은 400으로 답한다. (S1A 부검 2) |
| S1A-AC-85 | The system shall 비밀번호 찾기 요청만으로는 그 계정의 비밀번호, 세션, 잠금을 바꾸지 않는다. (S1A 부검 2) |
| S1A-AC-86 | While 정지된 계정의 요청이 처리되는 중에 세션이 지워져도, the system shall 그 계정의 그 뒤 요청을 모두 401로 답한다. 지워진 세션이 다시 저장되지 않는다. (S1A 부검 3) |
| S1A-AC-87 | The system shall 사진 응답에 저장한 형식의 `Content-Type`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-cache`, `ETag`를 붙인다. `If-None-Match`가 맞으면 304다. 권한 검사는 304를 줄 때도 한다. 모든 GET에 `no-store`를 바라는 S0-AC-101에서 사진만 예외다. (S1A 부검 4) |
| S1A-AC-88 | When 2MB 사진 여럿을 같은 때 올려도, the system shall 운영과 같은 힙(`-Xmx384m`)에서 죽지 않고 모두 201이나 503(`Retry-After`)으로 답한다. (S1A 부검 4, S0-AC-64와 같게) |
| S1A-AC-89 | The system shall 계정 검색어의 `%`와 `_`를 글자 그대로 찾는다. (S1A 부검 5) |
| S1A-AC-90 | When v0.1.0에서 올라온 설치에서, the system shall 이미 있던 `ACTIVE`·`SUSPENDED` 계정을 수락한 계정으로, `INVITED` 계정을 수락 전으로 다룬다. (S1A 부검 6, S1A-AC-35) |
| S1A-AC-91 | If 비밀번호가 바뀌기 전에 시작한 로그인이 바뀐 뒤에 끝나 세션이 남아도, then the system shall 그 세션의 요청을 401로 답한다. (S1A 부검 3, 계약 앞 실험) |

## 4. 테스트를 쓸 때 지킬 것

- 기준 하나에 테스트는 동작 하나꼴로 쓴다. 입력만 다른 경우는 매개변수 테스트 하나로 묶는다.
- 도움 클래스는 패키지마다 복사하지 않고 `support/`의 것을 쓴다. 첫 묶음에서 복사된 것(`SmtpGate`, `Membership`, `AccountStatus` 등)은 잠겨 있어 그대로 두고, 새 테스트는 한 벌만 쓴다.
- 잠긴 `AttachmentQuotaTest`가 테스트 순서에 기댄다. 이 묶음 테스트 단계에서 순서에 기대지 않는 새 클래스로 바꿀지 정한다.

## 4-1. 센 검증을 받는 기준

`process.md` "검증의 세기"대로, 아래 기준만 C-7의 일부러 틀린 구현, 숨긴 E2E, 변이 테스트를 받는다. 나머지는 테스트와 잠금, 자동 관문만 받는다.

| 까닭 | 기준 |
|---|---|
| 세션과 비밀번호 | S1A-AC-01~04, 05~14, 17, 18, 36, 37, 69, 82~86, 91 |
| 권한과 마지막 관리자 | S1A-AC-29, 32, 33, 56, 81 |
| 고객사 경계 | S1A-AC-25~27, 50, 52~55, 76~78 |
| 개인정보(사진, 감사 값) | S1A-AC-21, 23, 62~64, 87, 88 |
| 마이그레이션 | S1A-AC-80, 90 |

## 5. 메인이 추천해 넣었고 사용자가 그대로 두기로 한 것 (2026-10-11)

1. 본인의 비밀번호 변경에서 지금 비밀번호를 다섯 번 틀리면 로그인 잠금과 같이 막는다(S1A-AC-18). 열린 세션을 쥔 사람이 비밀번호를 마음껏 맞혀 보지 못하게 하려는 것이다.
2. 프로필 사진은 2,500만 픽셀(WebP는 1,200만, 계약 앞 실험 뒤 낮춤)을 넘으면 디코딩하지 않고(S1A-AC-23), 한 계정이 60초에 10번까지 올린다(S1A-AC-28). 작은 파일이 큰 그림으로 풀려 메모리를 다 쓰는 것을 막는다. 숫자는 추천값이다.
3. 역할을 바꿔도 상담원 배정은 지우지 않는다(S1A-AC-31). 관리자였다가 상담원으로 돌아오면 앞의 배정이 그대로 듣는다.
4. 수락한 적 없는 계정은 "정지 풀기"로 살리지 않고 "다시 보내기"로만 살린다(S1A-AC-35).
5. 관리자의 비밀번호 초기화는 새 비밀번호를 정해 주지 않고 재설정 링크를 보낸다. 지금 비밀번호는 바로 못 쓰게 된다. SMTP가 없으면 초대 링크 복사처럼 링크를 응답에 한 번 준다(S1A-AC-36, 37). 링크는 비밀번호 찾기와 같이 1시간이다.
6. 소속은 고객 계정만, 배정은 상담원 계정만 받는다(S1A-AC-51). 관리자는 배정 없이 전부 본다.
7. 본인의 비밀번호 변경도 감사 로그에 남긴다(S1A-AC-62). features의 AUDIT-1 목록에는 없었다.
8. 첫 묶음에서 넘어온 작은 것들은 3.11절대로 넣는다. 웹훅 3xx와 연결 실패 경로, `MAIL_FROM`은 4번 묶음으로, 내려받기 `Content-Type`은 5번 묶음으로, 감사 보관 기간 최소값은 1b로 넘긴다.
