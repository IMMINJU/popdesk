# b2 묶음 수용 기준: 작은 동작 고침

> `backlog.md` 라-1~라-4다. 묶음이 끝나면 `design/archive/b2-behavior/`로 간다(D-27).
> 전체 경로다: 공개 계약(콘솔 `CompanyMember`에 칸 하나, 담당자 지정의 400 하나)과 개인정보 흐름(익명 계정의 표시)에 닿는다. 마이그레이션은 없다. 사전 부검은 같은 폴더 `premortem.md`다.
> 기준 ID는 `B2-AC-nn`이다. 관계있는 결정: D-43, D-57. 앞 기준: S10A-AC-13·36(익명 계정 거절), S10B-AC-05(내보내기 실패 알림), S10B 자동 정리 켜짐 알림, S4B 받는 사람 한도.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-20). 메인이 정한 것은 3절에 모았다.

## 1. 지금 어떤가 (메인이 서브에이전트와 코드로 확인)

| 넘긴 일 | 지금 | 코드 |
|---|---|---|
| 라-1 | 익명 계정은 API에서 `SUSPENDED`다. C-9 소속 고객 표는 `status`만 보아 익명 고객을 "정지"로 그린다. "담당자로 지정"은 고객사가 보관됨일 때만 숨는다. 서버 `changeManager`는 고객사, 소속, 보관됨만 보고 익명 계정을 담당자로 정해도 200이다(소속 더하기 `addCustomer`는 `refuseAnonymized`로 막는다) | `ConsoleCompanyController` 59~62줄, `C9CompanyDetail.tsx` 745·753~765줄, `CompanyService` 322~349줄 |
| 라-2 | 앱이 뜰 때 `RUNNING`이던 고객사 내보내기를 `FAILED`·`INTERRUPTED`로 바꾸지만 알림을 보내지 않는다. 앱이 도는 중의 `INTERRUPTED`(멈추는 중, 잠금 잃음)와 쓰기 실패는 모든 관리자에게 `COMPANY_EXPORT_FAILED`를 보낸다. conventions는 "`FAILED`가 되면 모든 관리자에게"라고 적었다 | `CompanyExportService` 296~329줄과 374~385줄 |
| 라-3 | `ISSUE_RETENTION_ARMED`의 `retentionCount`가 0이어도 C-7이 "… 문의 0건을 휴지통으로 옮깁니다"라고 쓴다 | `C7Inbox.tsx` 80~81줄, `@popdesk/i18n` `inbox.retentionArmed` |
| 라-4 | 받는 사람 한도(한 주소 한 시간 50통)에 걸린 메일마다 `SendLoops`가 WARN 한 줄("Outbox row … was not sent: RATE_LIMITED. It is left as failed.")을 남긴다. 운영 경보 `DELIVERY_FAILED`는 이미 한 번만 올린다 | `SendLoops` 126~131줄, `OpsAlertService` 52~56줄 |

## 2. 수용 기준

| ID | 기준 |
|---|---|
| B2-AC-01 | The 콘솔 API shall 고객사의 소속 고객 목록(`CompanyMember`)에 `anonymized`(필수, 참·거짓)를 준다. 익명 계정만 참이다. 다른 칸은 그대로다(익명 계정의 `status`는 지금처럼 `SUSPENDED`). |
| B2-AC-02 | When 관리자가 익명 계정을 고객사의 담당자로 지정하면(`PUT …/customers/{accountId}/manager`), the system shall 400 `ACCOUNT_ANONYMIZED`로 거절하고 아무것도 바꾸지 않으며 감사 항목을 남기지 않는다. 검사 차례: 역할(403), 고객사(404), 소속(404), 익명(400), 보관된 고객사(400). |
| B2-AC-03 | When 관리자가 이미 담당자인 익명 계정의 담당자를 해제하면(`DELETE …/manager`), the system shall 지금처럼 받는다(익명화 앞에 담당자였던 계정을 풀 수 있다). |
| B2-AC-04 | The 콘솔 C-9 소속 고객 표 shall 익명 고객의 상태 칸에 "익명화됨"(C-10과 같은 배지)을 보이고, 그 줄에 "담당자로 지정"을 두지 않는다. 이미 담당자인 익명 고객의 줄에는 "담당자 해제"를 둔다. 이름·이메일 칸은 받은 값 그대로다. |
| B2-AC-05 | When 앱이 뜰 때 `RUNNING`이던 고객사 내보내기를 `FAILED`(`INTERRUPTED`)로 바꾸면, the system shall 그 내보내기마다 모든 활성 관리자에게 `COMPANY_EXPORT_FAILED`(`companyCode`는 그 고객사)를 남긴다. 메일과 웹훅은 없다(지금 그대로). 바꾼 것이 없으면 아무것도 남기지 않는다. |
| B2-AC-06 | While `ISSUE_RETENTION_ARMED`의 `retentionCount`가 0이면, the 콘솔 C-7 shall "문의 자동 정리가 켜졌습니다. 지금은 종료된 지 {days}일이 지난 문의가 없습니다."(영어 "Automatic issue cleanup is on. No issue has been closed for over {days} days yet.")를 보인다. 1 이상이면 지금 문장 그대로다. |
| B2-AC-07 | When 받는 사람 한도로 메일을 보내지 못하면(`RATE_LIMITED`), the system shall 그 메일마다 WARN을 남기지 않는다. 한도에 걸린 메일 하나하나는 INFO 줄("Outbox row {id} (MAIL) was not sent: RATE_LIMITED. It is left as failed.", 문장은 지금 코드 그대로)로 남고, WARN은 운영 경보 `DELIVERY_FAILED`가 올라갈 때의 한 줄뿐이다. 다른 까닭으로 멈춘 것(`STOPPED`의 다른 종류)은 지금처럼 WARN이다. |

## 3. 메인이 정한 것

| # | 정한 것 | 까닭 | 다른 길 |
|---|---|---|---|
| 1 | `CompanyMember`에 `anonymized`를 더한다(`status`에 새 값 `ANONYMIZED`를 넣지 않는다) | `Account`, `SuspendedAccount`가 이미 같은 꼴(`status` `SUSPENDED` + `anonymized`)이다. 응답에 칸을 더하는 것은 있는 클라이언트를 깨지 않는다. 새 enum 값은 깨뜨린다 | `status`에 `ANONYMIZED` |
| 2 | 익명 계정의 담당자 지정은 400 `ACCOUNT_ANONYMIZED`, 해제는 받는다 | 소속 더하기(S10A-AC-36)와 초대 링크(S10A-AC-13)가 같은 code로 거절한다. 해제까지 막으면 익명화 앞에 담당자였던 계정을 풀 길이 없다 | 익명화할 때 담당자를 자동으로 풂(익명화 거래를 넓힌다, 이번에 하지 않음) |
| 3 | 정지 계정의 담당자 지정은 그대로 둔다 | 정지는 되돌릴 수 있고 1a부터 그렇게 동작했다. 이번 일의 범위가 아니다 | 정지도 막음 |
| 4 | 뜰 때 끊긴 내보내기도 실패 알림을 남긴다 | conventions의 글("`FAILED`가 되면")과 맞춘다. 내보내기는 설치에 하나만 돌아 뜰 때 바뀌는 것은 많아야 하나다 | 기준 쪽 글을 고침(앱 재시작으로 끊긴 것은 알리지 않음) |
| 5 | 0건 문장은 따로 둔다 | "0건을 옮깁니다"는 무언가를 옮길 것처럼 읽힌다 | 0건이면 알림을 남기지 않음(정리가 켜졌다는 사실도 사라진다) |
| 6 | 한도에 걸린 메일 줄은 INFO로 내린다 | 같은 원인의 경보가 한 번 WARN으로 남는다. 자동 종료 뒤처럼 한꺼번에 수십 통이 걸리면 WARN이 줄마다 쌓여 다른 경고를 덮는다. 줄 하나하나는 INFO에 남아 추적할 수 있다 | 1분에 한 번 묶은 요약 줄(상태가 생긴다) |
| 7 | 숨긴 E2E는 B2-AC-01·02·04(익명 고객을 담당자로 지정하는 흐름) 하나를 쓴다 | 개인정보 흐름과 계약이 닿는 곳이다 | |

## 4. 화면

`screens.md`다. C-9 소속 고객 표의 한 칸과 한 단추, C-7 알림 문장 하나.

## 5. 테스트 쓰는 법 (C-5)

- 서버: B2-AC-01~03은 HTTP 테스트로(익명 계정은 `POST /api/console/accounts/{id}/anonymize`로 만든다. 10a 테스트의 꼴을 쓴다). B2-AC-02는 감사 항목이 늘지 않는 것도 본다. 고객사 경계 부정: 다른 고객사의 고객을 이 고객사 경로로 지정하면 지금처럼 404(익명이어도 404가 400보다 먼저).
- 서버: B2-AC-05는 `RUNNING` 행을 DB에 넣고 앱을 띄워(10b의 뜰 때 테스트 꼴) 관리자 알림함을 HTTP로 본다. 바꾼 것이 없으면 알림이 없는 것도.
- 서버: B2-AC-07은 로그를 잡는 공용 도움(있으면 그것을, 없으면 Logback `ListAppender`를 `support/`에 하나)으로 51번째 메일의 줄 수준을 본다. 경보 WARN 한 줄도.
- 웹: B2-AC-04, 06(jsdom). B2-AC-04는 익명 고객, 익명이면서 담당자인 고객, 정지 고객(그대로 "정지"와 "담당자로 지정")을 견본에 넣는다.
- 앞 묶음의 잠긴 테스트가 이 기준과 부딪히면 테스트 쪽이 고치고 까닭을 적는다.
