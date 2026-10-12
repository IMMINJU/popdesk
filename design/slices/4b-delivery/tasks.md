# 4b 묶음 작업 (C-8)

> 한 세션 크기로 나눈 구현 작업이다. 작업 하나는 "이 테스트들을 통과시킨다"다. 묶음이 끝나면 지운다(D-27).
> 탭 지시서는 `C:\Users\minju\.claude\popdesk\tasks\s4b-i-*.md`에 있다. 서버 탭과 웹 탭을 같이 띄운다. 웹 탭의 브라우저 확인만 서버가 합쳐진 뒤에 한다(`process.md` "같이 돌리는 일을 늘리려고 바꾼 것").

## 1. 탭과 차례

| 탭 | 맡은 것 | 통과시킬 테스트 |
|---|---|---|
| `s4b-i-server` (서버) | 샌드박스를 읽는 한 클래스와 그것을 쓰는 곳(메일, 웹훅 대기와 보내기, 감사 사본, 시작 검사와 경고, 시스템 상태), 한 주소에 한 시간 50통과 지표, 다시 보내기, 시험 메일, 감사 종류 둘, 멈춤 경보 셋, 정리 작업 목록, 오류 code `CHANNEL_DISABLED`, 운영 문서 | `notify`의 `SandboxMailTest`·`SandboxChannelsTest`·`SandboxMailLimitTest`·`SandboxNoAddressTest`·`SandboxOffTest`·`MailLimitTest`·`ResendTest`·`TestMailTest`·`TestMailCountsTest`, `start.SandboxMailToSettingTest`, `ops`의 `DeliveryAccessTest`·`DeliveryCheckOrderTest`·`SandboxChannelsOffTest`·`StalledAlertTest`·`CleanupJobsStatusTest`·`DeliverySecretsTest`·`TestMailLogTest` |
| `s4b-i-web` (웹, 브라우저) | C-18의 샌드박스 띠, 다시 보내기, 시험 메일, 정리 작업 표와 story, C-7의 새 경보 셋, C-12의 새 묶음과 바뀐 값의 글. 4a 검증이 넘긴 공용 CSS 둘(360px의 낱말 가운데 줄바꿈, `#comment-ID`로 간 댓글의 포커스 테두리) | 콘솔의 `C18SystemStatus.4b.test`, `C7Inbox.4b.test`, `C12AuditLog.4b.test`, 그리고 `pending.txt`에 올린 앞 묶음 파일 셋(`auditWords.words.test`, `C12AuditLog.filters.test`, `C12AuditLog.test`) |

## 2. 구현 탭이 코드로 지킬 것 (테스트로는 볼 수 없는 것)

| 것 | 까닭 | 맡는 탭 |
|---|---|---|
| 꺼진 채널의 `failed` 행을 다시 보내면 400 `CHANNEL_DISABLED`이고 행과 감사 로그가 그대로다(S4B-AC-17, 20) | HTTP로 그 행을 만들 길이 없어 잠긴 테스트가 없다. 서비스의 단위 테스트를 새 파일로 쓴다 | `s4b-i-server` |
| 샌드박스는 대기에 있던 웹훅 행을 보내지 않고 지운다(S4B-AC-04) | 서버 테스트로 볼 수 없다. 숨긴 E2E가 본다. 보내는 클래스의 단위 테스트를 쓴다(`AuditCopySenderTest`의 꼴) | `s4b-i-server` |
| 샌드박스 설정을 읽는 곳은 한 클래스다. 메일, 웹훅, 감사 사본, 시작 경고, 시스템 상태가 모두 그것에 묻는다 | `design.md` 0절 7. 읽는 곳이 여럿이면 한 곳이 빠진다 | `s4b-i-server` |
| 메일의 주소를 바꾸는 자리는 SMTP에 넘기기 직전 한 곳이다. 초대, 비밀번호 재설정, 시험 메일도 그 자리를 지난다. Cc와 Bcc를 쓰지 않는다 | `design.md` 1절 | `s4b-i-server` |
| 상한의 키는 실제로 나가는 주소(소문자)의 해시다. 주소 글자를 메모리에 오래 두지 않는다 | `design.md` 2절 | `s4b-i-server` |
| 시험 메일은 트랜잭션 밖에서 보낸다. 서버가 한 답의 글은 보내기 루프의 로그와 대기 행에 넣지 않는다 | `design.md` 3절 | `s4b-i-server` |
| `serverReply`는 예외가 `AuthenticationFailedException`, `SMTPAddressFailedException`, `SMTPSendFailedException`일 때만 채운다. 사용자 이름과 비밀번호(원래 꼴, Base64 꼴, AUTH PLAIN의 Base64 꼴)를 지우고 받는 주소는 가린다 | 계약 탭의 실험 1 | `s4b-i-server` |
| 올린 설치(견본)에서 멈춤 경보가 저절로 뜨지 않는다 | 뜨면 잠긴 `deploy/TalkUpgradeTest`의 `KINDS_BEFORE`가 깨진다. 꺼진 채널은 경보를 내지 않는다 | `s4b-i-server` |
| 서버가 한 답의 글은 `pre` 요소에 글자로만 넣는다 | `screens.md` 6절 10, S4B-AC-34 | `s4b-i-web` |

## 3. 읽을 보고

| 탭 | 읽을 절 |
|---|---|
| 서버 | `s4b-c4-server-report.md` 1·2·5·7절, `s4b-t-sandbox-report.md` 0절(도움)·2절·3절(가정), `s4b-t-resend-report.md` 3절(시험 메일의 글)·4절(가정), `s4b-c7-server-report.md`의 "통과하는 틀린 구현"(하지 말아야 할 것), 고치는 탭의 보고가 있으면 그것 |
| 웹 | `s4b-t-web-report.md` 5절(가정), `s4b-c7-web-report.md`의 "틀린 구현의 결과"와 "구현하면 깨질 잠긴 테스트", 고치는 탭의 보고가 있으면 그것 |
