# 12 묶음 수용 기준: 메일 받기

> `roadmap.md` 12번(MAILIN)이다. 묶음이 끝나면 `design/archive/12-mailin/`로 간다(D-27).
> 전체 경로다: 개인정보 흐름(받은 메일의 보낸 주소, 익명화 때 비움), 공개 계약(설정 키의 동작, 샌드박스의 뜻 바뀜, 보내는 메일의 머리글, 시스템 상태의 새 칸, 새 경보 종류, 새 정리 작업), 고객사 경계(메일로 남의 문의·고객사에 닿기), 마이그레이션(받은 메일 기록, 보낸 메일 기록). 사전 부검은 같은 폴더의 `premortem.md`다.
> 규칙 ID의 글은 같은 폴더 `rules.md`에 있다. 기준 ID는 `S12-AC-nn`이다.
> 관계있는 결정(`adr.md`): D-16, D-18, D-23, D-39, D-46, D-51, D-67·D-68(새). 위협 모델: TM-03, TM-22, TM-23, TM-24, TM-36, TM-70(새).
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-19). 규칙에 없던 것을 메인이 넣은 곳은 5절에 모았다.

## 1. 넣는 것과 미루는 것

지금: 설정 키 `IMAP_HOST`, `IMAP_PORT`, `IMAP_USER`, `IMAP_PASSWORD`, `IMAP_FOLDER`, `IMAP_ALLOW_PLAINTEXT`, `IMAP_PROCESSED_FOLDER`, `IMAP_QUARANTINE_FOLDER`, `MAIL_INBOUND_ADDRESS`는 `SettingKeys`와 `SecretFiles`, `configuration.md`에만 있고 읽는 코드가 없다 [소스: `SettingKeys.java:31-32`, `SecretFiles.java:29`]. `deploy/compose.yml`은 이 키들을 넘기지 않는다 [소스]. 보내는 메일은 `Message-ID`(Angus가 만든 값)를 저장하지 않고 `Reply-To`가 없다 [소스: `SmtpMail.java:153-158`]. 문의 표의 `channel`은 이미 `EMAIL`을 받고 콘솔 C-4가 "메일"로 보인다 [소스: V2 68줄, `C4IssueDetail.tsx:87`]. IMAP 제공자는 의존성(`angus-mail` 2.0.5)에 들어 있다 [소스].

| 기능 | 12에 넣는 것 | 미루는 것 |
|---|---|---|
| MAILIN-1~9 | 설정 검사, 회차, 버리기, 답장을 댓글로, 새 문의, 본문과 첨부, 요청 수 제한, 안내 메일, 받은 메일 기록, 격리 | XOAUTH2(D-68), 관리자가 검토하는 대기함(D-16), 메일로 온 댓글의 "메일" 표시, IMAP `IDLE` |
| MAIL-1·7 | 알림 메일의 `Reply-To`, `Message-ID` 짓기와 저장, `Auto-Submitted` 머리글 | – |
| OPS-1·3 | 시스템 상태(C-18)의 메일 받기 구역, 경보 둘 | 격리 폴더의 메일을 화면에서 다시 넣기 |
| D-46 | 샌드박스에서 메일 받기를 끔 | – |

## 2. 수용 기준

말의 뜻:

- "메일 받기가 켜진 설치"는 `IMAP_HOST`, `IMAP_USER`, `IMAP_PASSWORD`(또는 `IMAP_PASSWORD_FILE`), `MAIL_INBOUND_ADDRESS`가 모두 있고 샌드박스가 아닌 설치다.
- "회차"는 IMAP 서버에 한 번 붙어 받는 폴더를 한 번 훑는 일이다.
- "받은 메일 기록"은 메일 하나의 처리 결과를 담은 행이다. 메일의 열쇠는 "`From` 주소(소문자) + 줄바꿈 + `Message-ID` 머리글 값(앞뒤 `<>`와 공백을 뗀 것)"의 SHA-256이고, `Message-ID`가 없으면 `Message-ID` 자리에 IMAP 서버가 준 원문 바이트(`BODY[]`)의 SHA-256을 쓴다. `From`이 주소 하나가 아니면 원문 바이트의 SHA-256이다.
- "주소"는 머리글을 RFC 5322로 풀어 얻은 addr-spec만이다(표시 이름, 주석, 인코딩된 단어는 보지 않는다). 견줄 때 이름 부분은 ASCII 대소문자를 가리지 않고, 도메인은 IDN을 A-label로 바꿔 견준다.
- "버린다"는 문의·댓글·메일을 만들지 않고, 결과 `DISCARDED`와 까닭을 기록하고, 처리됨 폴더로 옮기는 것이다.
- "격리한다"는 문의·댓글·메일을 만들지 않고, 결과와 까닭을 기록하고, 격리 폴더로 옮기는 것이다.
- "안내 메일 A"는 "문의를 만들지 못했다"는 한 가지 문구의 메일, "안내 메일 B"는 "그 문의에 댓글을 달지 못했다"는 메일이다(`screens.md`).
- "볼 수 있는 문의"는 그 고객이 포털에서 열 수 있는 문의다(RULE-02: 자기 고객사의 공개 문의와 자기가 올린 문의, 휴지통에 없는 것).

### 2.1 설정과 시작

| ID | 기준 |
|---|---|
| S12-AC-01 | If `IMAP_HOST`, `IMAP_USER`, `IMAP_PASSWORD`, `MAIL_INBOUND_ADDRESS` 가운데 일부만 있거나, `IMAP_PORT`가 1~65535의 정수가 아니거나, `MAIL_INBOUND_ADDRESS`가 이메일 주소 하나가 아니거나 `+`가 든 주소이거나, 세 폴더(`IMAP_FOLDER`, `IMAP_PROCESSED_FOLDER`, `IMAP_QUARANTINE_FOLDER`) 가운데 빈 값이나 같은 값이 있으면, then the system shall 뜨지 않고 그 키와 까닭을 로그에 남긴다. 비밀값은 남기지 않는다. (MAILIN, 5절 3) |
| S12-AC-02 | When 설정이 맞게 있으면, the system shall IMAP 서버에 접속하지 않고 뜬다. IMAP 서버가 멈춰 있어도 앱이 뜬다. `IMAP_PORT`가 없으면 993이고, 연결 방식은 D-51을 따른다(993이면 암묵적 TLS, 그 밖이면 STARTTLS를 요구, `IMAP_ALLOW_PLAINTEXT=true`일 때만 평문과 시작 경고). (MAILIN, D-51) |
| S12-AC-03 | Where `POPDESK_SANDBOX=true`이면, the system shall `IMAP_*` 설정이 있어도 회차를 돌지 않고 알림 메일에 `Reply-To`를 달지 않으며, 시작 경고(`StartWarnings`)에 메일 받기가 꺼졌다고 적는다. (D-46, 5절 4) |

### 2.2 보내는 메일 (MAIL-1, MAIL-7)

| ID | 기준 |
|---|---|
| S12-AC-04 | The system shall 보내는 모든 메일에 `Message-ID: <난수@도메인>`(난수는 32바이트를 소문자 16진으로, 도메인은 `MAIL_INBOUND_ADDRESS`의 도메인이고 없으면 보내는 주소의 도메인)과 `Auto-Submitted: auto-generated`를 단다. 안내 메일(2.9)은 `Auto-Submitted: auto-replied`다. (MAIL-7, MAILIN-2, 5절 5) |
| S12-AC-05 | Where 메일 받기가 켜진 설치에서 문의 키가 제목에 든 메일을 고객에게 보내면, the system shall `Reply-To: MAIL_INBOUND_ADDRESS`를 달고 보낸 메일 기록(`Message-ID`, 문의)을 남긴다. 상담 쪽에 가는 메일, 키가 없는 메일(초대, 비밀번호 재설정, 시험 메일), 안내 메일에는 달지 않고 남기지 않는다. 기록은 SMTP 서버가 받은 뒤 따로 남긴다. 기록을 남기지 못해도 메일을 다시 보내지 않는다(그 메일의 답장은 제목 키로만 이어진다). (MAIL-1, MAIL-7) |

### 2.3 회차와 메일함

| ID | 기준 |
|---|---|
| S12-AC-06 | While 메일 받기가 켜져 있고 이 앱이 인스턴스 잠금을 쥐고 있으면(D-23), the system shall 앱이 뜬 직후와 그 뒤 앱 시계(주입한 `Clock`)로 1분마다 회차를 돈다. 한 회차는 받는 폴더의 모든 메일(읽음 표시와 관계없이)을 UID 차례대로 100통까지 본다. 메일을 읽어도 읽음 표시를 바꾸지 않는다. 사람이 지움 표시(`\Deleted`)를 해 둔 메일은 다루지 않고 그대로 둔다(받기 테스트 탭 질문 1, 메인이 정함). 처리됨·격리 폴더가 없으면 만든다. "회차 성공"은 붙고, 로그인하고, 받는 폴더를 끝까지 훑은 것이다(메일 하나의 처리 실패와는 상관없다). (MAILIN-8, 5절 6) |
| S12-AC-07 | When 메일 하나의 처리가 커밋되면, the system shall 그 뒤에 메일을 처리됨 폴더로(격리면 격리 폴더로) 옮긴다. 서버가 `MOVE`를 지원하면 그것으로, 아니면 `COPY`, `\Deleted` 표시, 그 메일만의 `UID EXPUNGE`(UIDPLUS)로 옮긴다. 둘 다 지원하지 않는 서버면 회차가 실패한다(종류 `UNSUPPORTED`. 사람이 지움 표시한 다른 메일까지 지우는 그냥 `EXPUNGE`는 쓰지 않는다). 옮기지 못하면(연결 끊김 등) 다음 회차가 기록을 보고 다시 처리하지 않고 옮기기만 한다. (MAILIN-6·8, 5절 16) |
| S12-AC-08 | When 이미 결과가 있는 열쇠의 메일이 다시 오면(같은 메일이 두 번 들어옴, 같은 `Message-ID`로 다시 보냄), the system shall 문의·댓글·안내 메일을 새로 만들지 않고 그 결과의 폴더로 옮긴다. 다만 결과가 `DISCARDED`(까닭 `DMARC_FAIL`)인 기록은 같은 열쇠의 다음 메일을 막지 않고, 그 메일을 처음 온 메일처럼 처리해 기록의 결과를 바꾼다(전달 서버를 거쳐 DMARC에 걸린 사본 뒤에 온 진짜 메일이 사라지지 않게). `Message-ID`가 없는 메일은 원문 바이트가 같을 때만 같은 메일이다(메일 서버를 다시 거치면 머리글이 붙어 달라지므로, 이 경우는 옮기기 실패 뒤 다시 읽는 것만 막는다). (MAILIN-6, 5절 17) |
| S12-AC-09 | If 메일 하나를 처리하다 앱이나 DB 오류가 나면, then the system shall 그 메일의 처리를 되돌리고 옮기지 않고 다음 회차에 다시 처리한다. 시도 수는 처리 앞에 따로 커밋해 센다. 다섯 번 시도한 메일은 다음 회차에서 처리하지 않고 결과 `QUARANTINED`(까닭 `FAILED`)로 격리한다. 크기(`RFC822.SIZE`)가 30MB를 넘는 메일은 내용을 받지 않고 바로 격리한다(까닭 `TOO_LARGE`). MIME 부분이 200개를 넘거나 10단계보다 깊으면 격리한다(까닭 `TOO_COMPLEX`). (MAILIN-8, 5절 7) |
| S12-AC-10 | If IMAP 서버에 붙지 못하거나(연결 10초, 읽기 30초), 로그인이 거절되거나, 받는 폴더가 없으면, then the system shall 그 회차를 끝내고 아무 메일도 옮기지 않고 실패 종류(`CONNECT`, `AUTH`, `FOLDER`, `UNSUPPORTED`)를 센다. 다음 회차는 1분 뒤 다시 붙는다. 앱의 다른 일(요청, 보내기 대기, 정리 작업)은 막히지 않는다. (MAILIN, OPS-3) |

### 2.4 버리기 (MAILIN-1, MAILIN-7, 보낸 사람 확인)

이 절의 차례대로 본다. 하나라도 걸리면 버리고 뒤는 보지 않는다.

| ID | 기준 |
|---|---|
| S12-AC-11 | If 메일에 `Auto-Submitted`가 있고 그 값이 `no`가 아니거나, `Precedence`가 `bulk`·`list`·`junk`·`auto_reply`이거나, `X-Autoreply`·`X-Autorespond` 머리글이 있거나, 맨 위 `Content-Type`이 `multipart/report`이거나, `Return-Path`가 `<>`이거나, `From`의 주소가 보내는 주소(`MAIL_FROM`, 없으면 S12-AC-04의 보내는 주소)이거나 `MAIL_INBOUND_ADDRESS`(그 `+코드` 꼴 포함)이면, then the system shall 버린다(까닭 `AUTOMATED`). 견줄 때 대소문자를 가리지 않는다. (MAILIN-7, TM-23) |
| S12-AC-12 | If 메일의 `Authentication-Results` 머리글(몇 개든 모두) 가운데 하나라도 방법 `dmarc`의 결과가 `fail`이면, then the system shall 버린다(까닭 `DMARC_FAIL`). 머리글은 접힌 줄을 펴고 RFC 8601의 꼴로 읽는다: `;`로 나눈 결과마다 주석(괄호)을 빼고, `=` 앞뒤 공백을 받고, 방법과 결과는 대소문자를 가리지 않는다. 결과가 `none`, `temperror`, `permerror`이거나 DMARC 결과가 없으면 받고 지표 `popdesk.mailin.unauthenticated`를 올린다. 다른 이름의 머리글(`ARC-Authentication-Results`, `X-MS-Exchange-Authentication-Results`)은 보지 않는다. (TM-22, D-68, 5절 17) |
| S12-AC-13 | If `From` 머리글이 정확히 하나가 아니거나 그 안의 주소가 정확히 하나가 아니거나, 그 주소(ASCII 대소문자 무시)의 계정이 없거나, 고객 계정이 아니거나(상담원, 관리자), `ACTIVE`가 아니면(수락 전, 정지, 익명), then the system shall 버리고(까닭 `UNKNOWN_SENDER`) 어떤 메일도 보내지 않는다. 로그에는 기록 ID, 까닭, 가린 주소(첫 글자 뒤로 가림) 한 줄만 남긴다. (MAILIN-1, D-16, PII-2) |
| S12-AC-14 | When 메일을 버리면, the system shall 받은 메일 기록에 보낸 주소와 계정을 남기지 않는다(열쇠, 결과, 까닭, 시각만). (MAILIN-6, PII-1, 5절 8) |

### 2.5 답장을 댓글로 (MAILIN-2)

| ID | 기준 |
|---|---|
| S12-AC-15 | When 받은 메일의 `In-Reply-To` 또는 `References`에 보낸 메일 기록의 `Message-ID`가 있고 그 문의를 보낸 사람이 지금 볼 수 있으면, the system shall 그 문의에 보낸 사람 이름으로 공개 댓글을 만든다. 포털에서 단 댓글과 같은 일(알림, 웹훅 `CUSTOMER_COMMENT`, 상태 변화)이 따른다. `In-Reply-To`와 `References`의 마지막 50개만 한 번의 조회로 본다. 찾은 기록 가운데 볼 수 있는 문의가 있으면 가장 뒤(최근)에 나온 것이고, 하나도 없으면 S12-AC-17이다. (MAILIN-2, CMT-1, 5절 17) |
| S12-AC-16 | When `Message-ID`로 문의를 찾지 못했고 제목에 `[키]`(대괄호 안이 포털의 키 꼴 `[A-Z0-9]{2,10}-[1-9][0-9]{0,8}`과 꼭 맞는 것 가운데 제목에서 처음 나온 것 [소스: `IssueKey.java:10`])가 있고 그 문의를 보낸 사람이 볼 수 있으면, the system shall S12-AC-15처럼 댓글을 만든다. 키는 대소문자를 가리지 않는다. (MAILIN-2) |
| S12-AC-17 | If `Message-ID`가 가리키는 문의를 보낸 사람이 볼 수 없으면(다른 고객사, 동료의 비공개 문의, 휴지통, 없음), then the system shall 답장으로 보지 않고 제목을 본다. 제목의 키가 볼 수 없는 문의이거나 없는 문의이면 문의를 만들지 않고 안내 메일 A를 보낸다. 제목에 키가 없으면 새 문의(2.6)로 본다. (MAILIN-2, TM-03, TM-36) |
| S12-AC-18 | If 문의를 볼 수 있지만 그 사람이 댓글을 달 수 없거나(올린 사람이 아님, 그 고객사 소속이 아님, CMT-1) 문의가 `CLOSED`이면, then the system shall 댓글을 만들지 않고 안내 메일 B(까닭 `CLOSED` 또는 `NOT_ALLOWED`, 그 문의의 포털 링크)를 보낸다. (MAILIN-2) |

### 2.6 새 문의 (MAILIN-3)

| ID | 기준 |
|---|---|
| S12-AC-19 | When 답장이 아닌 메일이 오면, the system shall 고객사를 이렇게 고른다: `To`·`Cc`의 주소 가운데 이름 부분의 첫 `+` 앞이 `MAIL_INBOUND_ADDRESS`의 이름 부분과 같고 도메인도 같은 것(처음 나온 것)의 `+` 뒤 값을 대문자로 바꿔, 고객사 코드 꼴(`[A-Z0-9]{2,10}` [소스: `CompanyService.java:52`])에 맞으면 그 코드(맞지 않으면 없는 코드로 본다), 없으면 보낸 사람의 소속이 하나일 때 그 고객사. (MAILIN-3, D-16) |
| S12-AC-20 | When 고른 고객사가 보낸 사람의 소속이고 `ACTIVE`이면, the system shall 보낸 사람을 올린 사람으로 문의를 만든다: 제목은 메일 제목(인코딩된 단어를 푼 뒤 NFC, 줄바꿈과 제어 문자(C0, C1, U+2028·2029)는 공백, 앞뒤 공백 뺌, 200자까지, 비면 그 계정 언어로 "(제목 없음)"/"(no subject)"), 내용은 본문(2.7), 우선순위 `NORMAL`, 공개, 채널 `EMAIL`. 포털에서 올린 문의와 같은 일(웹훅 `ISSUE_CREATED`, SLA)이 따른다. 포털 문의처럼 알림함 항목은 만들지 않는다. (MAILIN-3, ISSUE-2) |
| S12-AC-21 | If 소속이 여럿인데 코드가 없거나, 코드가 그 사람의 소속이 아니거나(없는 코드 포함), 소속인 고객사가 `ACTIVE`가 아니거나, 소속이 하나도 없으면, then the system shall 문의를 만들지 않고 안내 메일 A를 보낸다. 소속을 상태보다 먼저 보고, 어느 까닭이든 메일과 기록에서 남이 볼 수 있는 값(문구, 머리글)이 같다. (MAILIN-3, TM-36) |

### 2.7 본문 (MAILIN-4)

| ID | 기준 |
|---|---|
| S12-AC-22 | The system shall 본문으로 첨부가 아닌 첫 `text/plain` 부분(첨부한 `message/rfc822` 안은 보지 않는다)을 쓰고, 없으면 첫 `text/html` 부분을 글자로 바꿔 쓴다(블록 요소와 `<br>`는 줄바꿈, `script`·`style`·`head`는 버림, 링크는 "글 (주소)", 문자 참조를 풂). 머리글과 부분의 문자 집합을 풀고 NFC로 맞춘다. 모르거나 깨진 문자 집합은 오류로 보지 않고 UTF-8로 읽어 풀 수 없는 바이트를 대체 문자로 바꾼다. 본문이 비면 그 계정 언어로 "(내용 없음)"/"(no content)"이다. (MAILIN-4) |
| S12-AC-23 | When 메일이 댓글이 되면, the system shall 인용 구분선의 첫 줄부터 끝까지를 잘라 낸다. 구분선은 `On … wrote:`로 끝나는 줄, `… 작성:`으로 끝나는 줄(`님이 작성:` 포함), `-----Original Message-----`, `-----원본 메시지-----`, 그리고 끝에 몰린 `>` 인용 줄이다. 자른 뒤 끝의 빈 줄을 뺀다. 새 문의는 자르지 않는다. (MAILIN-4, 5절 9) |
| S12-AC-24 | If 본문이 한도(문의 10,000자, 댓글 4,000자, 글자 세는 법은 `conventions.md`)를 넘으면, then the system shall 맺는 줄 "(메일이 길어 앞부분만 옮겼습니다.)"/"(The email was too long; only its beginning was kept.)"을 붙여도 한도 안이 되게 앞에서부터 자른다. (MAILIN-4) |

### 2.8 첨부 (MAILIN-5)

| ID | 기준 |
|---|---|
| S12-AC-25 | When 메일에 파일 이름이 있는 부분(`attachment`든 `inline`이든, 본문의 그림 포함)이 있으면, the system shall 그것을 문의나 댓글의 첨부로 올린다. 포털과 같은 규칙(파일 하나 10MB, 한 번에 20개, 문의의 공개 첨부 합계 50MB, 이름 255자, 설치 전체 한도, ATT-2·3)을 따른다. 이름이 없는 부분은 첨부로 보지 않는다. 이름은 RFC 2231과 인코딩된 단어를 푼 뒤 경로 앞부분(마지막 `/`, `\`까지)을 떼고, 제어 문자(C0, C1, U+2028·2029)와 양방향 제어 문자(U+202A~202E, U+2066~2069)를 빼고, NFC로 맞춘다. 남는 것이 없으면 `attachment-{순번}`이다. 첨부의 크기는 전송 인코딩을 푼 뒤의 크기다. (MAILIN-5, ATT-10, 5절 17) |
| S12-AC-26 | If 첨부가 그 규칙에 맞지 않으면, then the system shall 그 파일만 빼고 본문 끝에 "빠진 첨부: {이름} ({까닭})" 줄을 그 계정 언어로 파일마다 붙인다(까닭: 빈 파일, 10MB 넘음, 개수 넘음, 문의의 합계 넘음, 저장 한도 넘음, 이름이 김. 이름은 255자에서 자른다). 그 줄도 한도(S12-AC-24) 안에 든다. (MAILIN-5) |
| S12-AC-27 | If 첨부를 저장소에 쓰지 못하면(저장소 오류), then the system shall S12-AC-09의 오류로 다룬다(문의·댓글을 만들지 않고 다음 회차에 다시). (MAILIN-6, ATT-10) |

### 2.9 요청 수 제한과 안내 메일

| ID | 기준 |
|---|---|
| S12-AC-28 | The system shall 버리지 않은 메일마다 그 계정의 포털 요청 수 제한(COM-8: 문의 만들기 60초에 10번, 댓글 60초에 30번, 첨부 60초에 30개)을 포털과 같은 셈(같은 계정의 같은 수)으로 센다. 문의는 문의 만들기로, 댓글과 안내 메일 B가 될 메일은 댓글로, 안내 메일 A가 될 메일은 문의 만들기 한 번으로 센다. 다시 시도한 메일은 다시 센다. 넘으면 격리하고(결과 `RATE_LIMITED`) 안내 메일을 보내지 않는다. (MAILIN-9, 5절 10) |
| S12-AC-29 | When 안내 메일을 보내면, the system shall 보내기 대기(D-39)로 그 계정 ID에 보내고(받는 주소는 그 계정에 등록된 주소다. `From`, `Reply-To`, `Return-Path`, `Sender`로 보내지 않는다), 그 계정의 언어로 쓰고, `Reply-To`를 달지 않는다. 제목에 대괄호 키를 넣지 않는다. 안내 메일 A에는 어떤 문의의 키·제목도, 고객사 코드도 넣지 않는다. 같은 계정에는 안내 메일을 15분에 한 통만 보낸다(그 사이의 메일은 같은 결과로 기록되고 메일만 나가지 않는다). (MAILIN-2·3, TM-36, 5절 11) |

### 2.10 기록, 익명화, 보관, 로그

| ID | 기준 |
|---|---|
| S12-AC-30 | The system shall 받은 메일 기록의 결과를 `ISSUE`(만든 문의), `COMMENT`(만든 댓글), `NOTICE`(안내 메일, 까닭 `NO_COMPANY`, `NOT_VISIBLE`, `CLOSED`, `NOT_ALLOWED`), `DISCARDED`, `QUARANTINED`, `RATE_LIMITED` 가운데 하나로 남긴다. 결과가 나기 전에는 시도 수만 있다. 같은 열쇠의 기록은 하나뿐이다(유일 제약). 결과와 문의·댓글은 같은 트랜잭션에서 커밋된다. (MAILIN-6) |
| S12-AC-31 | When 관리자가 계정을 익명화하면(PII-4), the system shall 같은 트랜잭션에서 그 계정이 보낸 받은 메일 기록의 보낸 주소를 비운다. (MAILIN-6, PII-4) |
| S12-AC-32 | When 문의가 영구 삭제되면(TRASH-3), the system shall 그 문의의 보낸 메일 기록을 지우고, 받은 메일 기록의 문의·댓글 연결을 비운다(결과는 남는다). (MAIL-7, TRASH-3) |
| S12-AC-33 | The system shall 정리 작업 `mail-record-cleanup`(하루 한 번)으로 90일이 지난 받은 메일 기록과 보낸 메일 기록을 지우고, 다른 정리 작업처럼 돈 시각과 지운 수를 남긴다(OPS-2). 결과가 없는 기록(시도 중)도 90일이 지나면 지운다. (MAIL-7, PII-6, 5절 12) |
| S12-AC-43 | While 메일 받기가 켜져 있으면, the system shall 하루 한 번 회차 끝에 처리됨 폴더와 격리 폴더에서 IMAP 서버가 받은 날(`INTERNALDATE`)이 90일 지난 메일을 지운다(그 메일만 `\Deleted`와 `UID EXPUNGE`). 버린 메일의 원문도 이렇게 90일 뒤 사라진다. 받는 폴더의 메일은 지우지 않는다. (PII-1, PII-6, 5절 18) |
| S12-AC-34 | The system shall 메일 받기의 로그에 본문, 제목, 첨부 이름, `Message-ID`, 가리지 않은 주소, IMAP 비밀번호를 남기지 않는다. 메일 하나의 로그는 기록 ID, 결과, 까닭과 만든 문의 ID·댓글 ID만 남긴다(S12-AC-13의 가린 주소는 예외). 메일 라이브러리의 예외는 클래스 이름만 남긴다(예외 글에 머리글이 그대로 들 수 있다). 메일 라이브러리의 디버그 로그(`mail.debug`)는 끈다. (PII-2, 5절 17) |

### 2.11 지표, 상태, 경보

| ID | 기준 |
|---|---|
| S12-AC-35 | The system shall 지표 `popdesk.mailin.messages`(태그 `outcome`, 결과마다), `popdesk.mailin.unauthenticated`, `popdesk.mailin.round.failures`(태그 `kind`)를 낸다. (MAILIN-1·8·9, OPS) |
| S12-AC-36 | When 관리자가 `GET /api/console/system/status`를 부르면, the system shall `mailIn`에 켜짐 여부, 꺼진 까닭(`NOT_SET`, `SANDBOX` 또는 `null`), 마지막으로 성공한 회차의 시각, 앱이 뜬 뒤 실패한 회차 수, 마지막 실패(종류 `CONNECT`·`AUTH`·`FOLDER`·`UNSUPPORTED`와 시각, 또는 `null`), 받는 폴더에 남아 다시 시도할 메일 수(결과 없이 시도 수가 1 이상인 기록), 90일 안에 격리한 메일 수(`QUARANTINED`와 `RATE_LIMITED`)를 준다. 회차 시각과 실패는 메모리에서 세며 앱이 다시 뜨면 비어 있다. (OPS-3, 5절 13) |
| S12-AC-37 | When 회차가 한 시간 넘게 성공하지 못하면(실패가 시작된 때부터 앱 시계로), the system shall 관리자 전부의 알림함에 `MAIL_IN_STALLED`를 남긴다. When 메일을 격리하면, the system shall `MAIL_IN_QUARANTINED`를 남긴다. `MAIL_IN_STALLED`는 회차가 성공해야만 풀린다(실패만 이어지거나 앱이 다시 떠도 풀리지 않는다). `MAIL_IN_QUARANTINED`는 24시간 동안 격리가 없으면 풀린다. 둘 다 OPS-1의 규칙(같은 종류는 풀리기 전까지 하루에 한 건)을 따르고, 알림은 시스템 상태 화면으로 이끈다. (OPS-1, 5절 14) |

### 2.12 화면

| ID | 기준 |
|---|---|
| S12-AC-38 | When 관리자가 C-18 시스템 상태를 열면, the system shall OIDC 구역 바로 뒤에 "메일 받기" 구역을 보인다: 켜짐·꺼짐(까닭), 마지막 성공 회차, 실패한 회차 수와 마지막 실패(종류 문장, 시각), 다시 시도할 메일 수, 90일 안에 격리한 메일 수(`screens.md`). (OPS-3) |
| S12-AC-39 | When 알림함(C-7)에 `MAIL_IN_STALLED`·`MAIL_IN_QUARANTINED`가 있으면, the system shall `screens.md`의 문장을 보이고 누르면 `/system`으로 간다. (OPS-1) |

### 2.13 마이그레이션, 견본, 문서

| ID | 기준 |
|---|---|
| S12-AC-40 | The system shall V25로 받은 메일 기록 표(`mail_received`)와 보낸 메일 기록 표(`mail_sent`)를 더한다. 둘 다 `tenant_id`가 있고(D-18), 받은 메일은 열쇠가, 보낸 메일은 `Message-ID`가 하나뿐이다. (MAILIN-6, MAIL-7) |
| S12-AC-41 | The system shall 업그레이드 견본이 받은 메일 기록 둘(댓글이 된 것, 버린 것)과 보낸 메일 기록 하나를 두고(IMAP 없이 만들 수 없어 SQL로), 견본으로 올린 설치가 뜬다. |
| S12-AC-42 | The system shall `docs/operate/configuration.md`에 메일 받기 절(전용 메일함, 세 폴더, `MAIL_INBOUND_ADDRESS`와 `+코드`, 답장이 이어지는 법, DMARC 확인과 받는 메일 서버가 `Authentication-Results`를 달아야 한다는 것, 비밀번호 IMAP만 되고 XOAUTH2가 없다는 것과 그래서 Microsoft 365는 붙지 않고 Google Workspace는 앱 비밀번호가 필요하다는 것)을 쓰고, 샌드박스 줄의 "Incoming email is not turned off"를 고친다. `docs/operate/privacy.md`에 버린 메일은 주소를 남기지 않는다는 것과 익명화 때 비우는 것, `deploy/compose.yml`이 `IMAP_*` 여덟과 `IMAP_PASSWORD_FILE`, `MAIL_INBOUND_ADDRESS`를 넘기고, 릴리스 노트에 새 머리글(`Message-ID`, `Auto-Submitted`, `Reply-To`)과 샌드박스의 바뀐 뜻을 적는다. (MAILIN, PII-1, D-46, D-68) |

## 3. 센 검증을 받는 기준

| 기준 | 까닭 |
|---|---|
| S12-AC-13, 15~21, 29 | 고객사 경계(남의 문의에 댓글, 다른 고객사에 문의, 안내 메일로 새는 것) |
| S12-AC-12, 13 | 보낸 사람 위조(권한) |
| S12-AC-14, 31, 34, 43 | 개인정보 |
| S12-AC-06~09, 30 | 한 번만 처리(두 번 생기거나 사라지는 문의) |
| S12-AC-40, 41 | 마이그레이션 |
| S12-AC-01, 03, 04, 05, 36 | 공개 계약 |
| S12-AC-08, 13, 15, 17, 19~21, 28, 31 | 숨긴 E2E로 본다: IMAP 대역 하나, 알림 메일에 답장해 댓글, 제목 키로 댓글, 다른 고객사 키로 안내, `+코드`로 문의, 소속 둘에 코드 없음, 모르는 보낸 사람, 같은 메일 두 번, 자동 응답, 요청 수 넘김, 익명화 뒤 주소 비움 |

일부러 틀린 구현(C-7 점검 지시서에 넣음): 볼 수 있는지 보지 않고 `Message-ID`로 댓글을 닮, 제목 키로 다른 고객사 문의에 댓글을 닮, 볼 수 없는 키가 든 메일로 새 문의를 만듦, 동료의 공개 문의에 올린 사람이 아닌데 댓글을 닮, 소속이 아닌 `+코드`로 문의를 만듦, 상태를 소속보다 먼저 봐 안내 문구가 갈림, 안내 메일 A에 문의 키를 넣음, 정지 계정의 메일을 받음, 상담원 계정의 메일을 받음, 모르는 보낸 사람에게 안내 메일을 보냄, `Auto-Submitted: auto-replied`를 받음, `dmarc=fail`을 받음, 커밋 앞에 메일을 옮김, 기록을 다른 트랜잭션에 남겨 두 번 처리됨, 실패한 메일을 바로 격리함, 시도 수를 처리 트랜잭션 안에서 세어 되돌려짐, 요청 수 제한을 세지 않음, 버린 메일에 주소를 남김, 익명화가 주소를 남김, 로그에 주소를 그대로 남김, 샌드박스에서 회차를 돎, 결과를 서비스 트랜잭션 밖에서 따로 커밋함, `From` 머리글 둘 가운데 뒤의 것을 씀, 표시 이름 안의 `+코드`나 주소를 씀, `DMARC=Fail`(대문자)이나 접힌 머리글을 놓침, 첨부 이름의 줄바꿈을 본문 줄에 그대로 넣음, 처리됨 폴더의 90일 지난 메일을 남김.

순수 계산 클래스(C-8 목록 후보): `MailInSettings`(환경 값 → 켜짐 또는 문제 목록), `AutomatedMail`(머리글 → 버릴 까닭 또는 없음, S12-AC-11·12), `IssueKeyInSubject`(제목 → 키 또는 없음), `InboundAddress`(받는 주소들과 `MAIL_INBOUND_ADDRESS` → 코드 또는 없음), `HtmlText`(HTML → 글), `QuoteTrim`(댓글 본문 → 자른 본문), `BodyFit`(본문, 덧붙일 줄들, 한도 → 한도 안의 본문). C-10에서 PIT를 받는다.

## 4. 화면

`screens.md`다. C-18의 메일 받기 구역, C-7 알림 문장 둘, 메일 문구(안내 메일 A·B, 본문에 붙는 줄).

## 5. 메인이 정한 것

| # | 정한 것 | 까닭 | 다른 길 |
|---|---|---|---|
| 1 | 보낸 사람 확인: `Authentication-Results` 가운데 하나라도 `dmarc=fail`이면 버리고, 결과가 없으면 받되 지표를 올린다(D-68, 위협 모델 6절의 정할 것을 닫음) | 버리는 쪽으로만 쓰므로 머리글을 위조한 사람은 자기 메일만 버리게 할 수 있다. 그래서 어느 서버가 단 머리글인지(`authserv-id`) 가릴 설정 키가 필요 없다. 결과를 꼭 요구하면 머리글을 달지 않는 메일 서버를 쓰는 곳에서 아무 메일도 받지 못한다. `p=none` 도메인의 `dmarc=fail`도 버린다(그런 도메인의 위조를 막을 길이 이것뿐이다) | `dmarc=pass`를 요구 / `authserv-id` 설정 키 / 확인 안 함 |
| 2 | XOAUTH2는 넣지 않는다. 문서에 Microsoft 365는 붙지 않고 Google Workspace는 앱 비밀번호가 필요하다고 적는다(D-68) | 제공자마다 앱 등록, 토큰 갱신, 관리자 동의가 따로라 설정 키가 여럿 는다. GreenMail은 `AUTH=XOAUTH2`를 받지만 [소스: 안정성 검토가 jar에서 읽은 CAPABILITY] 토큰을 내주는 제공자 쪽(갱신, 만료)은 흉내 내지 못한다. 지금 목표(받아서 써 보기)에서는 전용 메일함과 비밀번호 IMAP을 쓰는 곳이 먼저다. 프로젝트 사전 부검 5절의 "ADR로 정한다"를 닫는다 | XOAUTH2(Google, Microsoft) 같이 넣기 |
| 3 | 켜지는 조건에 `MAIL_INBOUND_ADDRESS`를 넣는다. 일부만 있으면 뜨지 않는다 | `+코드`와 `Reply-To`가 이 주소로 정해진다. 없으면 답장이 보내는 주소로 가서 이어지지 않는다. OIDC(S11-AC-01)와 같이 반쯤 맞은 설정을 조용히 끄지 않는다 | 없으면 `IMAP_USER`로 대신 |
| 4 | 샌드박스는 메일 받기를 끈다. 지금 문서의 "Incoming email is not turned off"를 고친다 | D-46이 정한 뜻이고 1b에서 12번으로 넘겼다. 운영 DB를 복사한 시험 설치가 운영 메일함을 먼저 읽어 가는 일(프로젝트 사전 부검 4절)을 막는다 | 문서대로 두기 |
| 5 | 모든 메일에 `Message-ID`를 popdesk가 짓고 `Auto-Submitted: auto-generated`를 단다 | 저장할 값을 앱이 알아야 한다. 32바이트 난수라 남의 알림 메일 `Message-ID`를 짐작해 답장할 수 없다(TM-36). `Auto-Submitted`는 고객 쪽 자동 응답이 우리 메일에 답하지 않게 한다(RFC 3834) | Angus가 지은 값을 읽어 저장 / 머리글 없음 |
| 6 | 회차는 `Housekeeping`처럼 1초마다 깨는 스레드가 앱 시계로 1분을 잰다 | 테스트가 공용 `TestClock`을 옮겨 회차를 부를 수 있고 설정 키를 늘리지 않는다 [소스: `Housekeeping.java:26-28`, `support/TestClock.java`]. 한 회차 100통은 회차 하나가 오래 쥐지 않게 한 것이다 | 회차 간격 설정 키 / IMAP `IDLE` |
| 7 | 시도 수는 처리 앞에 따로 커밋한다. 30MB 넘는 메일은 받지 않고 격리한다 | 처리 트랜잭션 안에서 세면 오류 때 같이 되돌아가 끝없이 시도한다. 앱을 죽이는 메일(메모리)도 다섯 번 뒤 빠진다. 첨부 한도가 문의당 50MB이고 base64는 3분의 4로 불어 30MB 메일이면 첨부가 20MB 남짓이다 | 메모리에서 셈(다시 뜨면 0) / 크기 한도 없음 |
| 8 | 버린 메일의 기록에는 주소를 남기지 않는다 | 모르는 사람의 주소를 쌓을 까닭이 없다. 두 번 처리하지 않는 데는 열쇠만 있으면 된다. `privacy.md`가 이미 "문의와 댓글이 된 메일의 보낸 주소"라고 적었다 [소스: `privacy.md` 19줄] | 모든 기록에 주소 |
| 9 | 인용 구분선에 한국어 메일 프로그램의 꼴(`… 작성:`, `-----원본 메시지-----`)을 더한다. 새 문의는 자르지 않는다 | 화면이 한국어로 나가고 Gmail 한국어판과 Outlook 한국어판이 그 꼴을 쓴다 [추론: C-5 테스트 탭이 실제 견본을 구해 확인]. 새 문의는 전달한 메일일 때가 많아 아래 내용이 문의의 본문이다 | 영어 꼴만 |
| 10 | 안내 메일이 될 메일도 문의 만들기 한 번으로 센다 | 자동 응답 머리글을 달지 않는 서버와 안내 메일이 서로 돌 때 막는 것이 요청 수 제한뿐이다 | 안내 메일은 세지 않음 |
| 11 | 같은 계정에 안내 메일은 15분에 한 통 | 남이 그 고객 주소로 위조한 메일(DMARC가 없는 도메인)로 그 고객에게 안내 메일을 퍼붓지 못하게 한다. 비밀번호 찾기(AUTH-7)의 15분과 같은 값이다 | 한도 없음(MAIL-6의 시간당 50통만) |
| 12 | 90일 보관은 새 정리 작업 하나로 지운다 | `privacy.md`가 90일을 약속했다 [소스: 35·36줄]. 회차 안에서 지우면 메일 받기를 끈 설치에 기록이 영영 남는다. 정리 작업 목록을 쥔 잠긴 테스트(`ops/CleanupStall`, `IdempotencyKeyTest`, `PasswordResetCleanupTest`의 목록)를 테스트 탭이 맞춘다(10b에서 두 작업을 더할 때와 같다) | 회차 안에서 지움 |
| 13 | 시스템 상태는 회차 시각과 실패를 메모리에서, 다시 시도할 수와 격리 수는 DB에서 센다 | OIDC(S11-AC-30)와 같은 꼴이다. 격리한 메일은 사람이 메일함에서 꺼내야 하므로 다시 떠도 수가 남아야 한다. 잠긴 `ops/SystemStatusTest`가 칸 목록을 정확히 보므로 테스트 탭이 맞춘다 [소스: 그 파일 32줄] | 모두 메모리 |
| 14 | 경보 둘(`MAIL_IN_STALLED`, `MAIL_IN_QUARANTINED`) | 메일 받기가 멈추면 고객은 보냈는데 아무도 모른다. 보내기 채널에는 한 시간 경보가 이미 있다 [소스: `OpsAlerts.java`의 `MAIL_STALLED`]. 격리한 메일은 사람이 보지 않으면 사라진 것과 같다 | 경보 없음 / 격리는 상태 화면에만 |
| 15 | 메일로 온 댓글에 "메일" 표시는 넣지 않는다 | 댓글 표에 채널 칸이 없어 칸과 계약이 하나씩 는다. 문의는 이미 채널을 보인다 | 댓글 `channel` 칸 |
| 16 | 옮기기는 `MOVE`, 없으면 `COPY`와 그 메일만의 `UID EXPUNGE`. 둘 다 없는 서버는 실패로 보인다 | 그냥 `EXPUNGE`는 사람이 같은 폴더에서 지움 표시만 해 둔 다른 메일까지 지운다. Angus는 `MOVE`가 없을 때 다른 길로 돌지 않는다 [소스: 안정성 검토, `IMAPProtocol`의 글] | 그냥 `EXPUNGE` |
| 17 | 보안 검토(2026-10-19, 서브에이전트)에서 받은 것: 열쇠에 `From` 주소를 넣고 `DMARC_FAIL`로 버린 기록은 뒤 메일을 막지 않음, DMARC 머리글을 RFC 8601로 읽음, `From` 머리글이 둘이면 버림, 주소는 addr-spec만, `+코드`는 첫 `+`와 코드 꼴, 문의 키 꼴을 `IssueKey`와 같게, `References` 50개, MIME 부분 200·깊이 10, `message/rfc822` 안은 본문이 아님, 깨진 문자 집합은 대체 문자, 첨부 이름과 제목의 제어·양방향 문자, 반송·자동 응답 머리글 더함, 예외는 클래스 이름만, 안내 메일은 등록 주소로만 | 위조와 파서 차이로 경계를 건너는 길을 기준 글에서 막는다. 받지 않은 것: 메일 받기의 요청 수를 포털과 따로 세기(MAILIN-9가 "포털과 같은"이라 했고, DMARC가 없는 도메인으로 위조해 그 고객의 포털 문의를 1분 막는 것은 TM-70의 남는 위험으로 둔다), `Sender` 도메인 확인(메일링 목록이 정상으로 쓴다) | 검토 전 판 |
| 18 | 처리됨·격리 폴더의 메일도 90일 뒤 지운다 | DB 기록만 지우면 메일함에 원문(주소, 본문, 첨부)이 영영 남아 S12-AC-14와 `privacy.md`의 90일이 말뿐이다(보안 검토) | 운영자에게 맡기고 문서에만 적기 |
| 19 | 안정성 검토(2026-10-19, 서브에이전트)에서 받은 것: 문의·댓글 서비스는 각자 새 트랜잭션을 연다(`Transactions`가 `REQUIRES_NEW` [소스: `Transactions.java:27`, `IssueService.java:355`, `CommentService.java:116`]). 그래서 결과는 서비스의 트랜잭션 안(훅)에서 쓴다(`design.md` 0절 9). 요청 수는 서비스가 이미 세므로 따로 세지 않는다. 댓글 첨부는 댓글 트랜잭션 안에서 붙이는 새 메서드가 필요하다. 보낸 메일 기록은 `OutboxDelivery.sent`의 트랜잭션에서 넣는다. 시험용 GreenMail은 메일 받기 전용으로 따로 둔다(공용 것은 테스트마다 모든 메일함을 비운다 [소스: `MailServer.java:105-111`]). TLS 연결 설정은 순수 계산으로 떼어 단위 테스트로 본다(GreenMail IMAP에 STARTTLS가 없다) | 기준 글대로 짜면 결과와 문의가 따로 커밋되어 3절의 "두 번 처리됨"이 그대로 생긴다 | 검토 전 판 |
