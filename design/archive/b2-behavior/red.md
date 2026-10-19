# 실패 확인

- 단언 실패 17, 통과 0, 허용한 통과 5, 오류 0, 건너뜀 0
- 판정: 통과

## 처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)

- `io.popdesk.notify.MailLimitLogTest.refusedMailIsStillWarn`: 다른 까닭의 실패 줄이 WARN으로 남는 것(바뀌지 않아야 할 것). 모든 줄을 INFO로 내리는 틀린 구현을 잡는다
- `io.popdesk.privacy.AnonymizedManagerTest.otherCompanyIsNotFound(boolean)[1]`: 다른 고객사 고객의 지정은 지금도 404다. 익명 검사가 소속 검사 앞에 오는 틀린 구현을 잡는다(B2-AC-02 검사 차례)
- `io.popdesk.privacy.AnonymizedManagerTest.otherCompanyIsNotFound(boolean)[2]`: 같은 테스트의 익명 아닌 경우, 있던 동작(S2-AC-02)
- `io.popdesk.privacy.CompanyExportQuietStartTest.noExportNoAlert`: 바꾼 것이 없으면 알림이 없다. 뜰 때마다 알리는 틀린 구현을 잡는다
- `src/screens/C7Inbox.b2.test.tsx.C-7 자동 정리 켜짐 항목의 문장 (b2 묶음) > B2-AC-06 C-7 retentionCount 3: 10b 문장 그대로 "… 문의 3건을 휴지통으로 옮깁니다"`: 1건 이상은 지금 문장 그대로(바뀌지 않아야 할 것)

## 단언에서 실패한 테스트

- `io.popdesk.notify.MailLimitLogTest.stoppedMailIsInfo`: [lines of the stopped row 78] 
- `io.popdesk.privacy.AnonymizedManagerTest.listCarriesAnonymized`: [anonymized of the anonymized customer] 
- `io.popdesk.privacy.AnonymizedManagerTest.anonymizedComesBeforeArchived`: PUT /api/console/companies/C000K/customers/9/manager on console.popdesk.test: expected the code ACCOUNT_ANONYMIZED but got COMPANY_NOT_ACTIVE: {"code":"COMPANY_
- `io.popdesk.privacy.AnonymizedManagerTest.markingAnonymizedIsRefused`: PUT /api/console/companies/C000N/customers/10/manager on console.popdesk.test: expected 400 but got 200: {"accountId":10,"name":"삭제된 사용자","email":"deleted-10@in
- `io.popdesk.privacy.AnonymizedManagerTest.anonymizedManagerIsUnmarked`: [anonymized of {"accountId":11,"name":"삭제된 사용자","email":"deleted-11@invalid","status":"SUSPENDED","photoUpdatedAt":null,"manager":true,"anonymized":false} befor
- `io.popdesk.privacy.CompanyExportRestartAlertTest.interruptedExportAlerts`: Timed out after PT15S waiting for a COMPANY_EXPORT_FAILED item in the inbox of admin@popdesk.test
- `src/screens/C7Inbox.b2.test.tsx.C-7 자동 정리 켜짐 항목의 문장 (b2 묶음) > B2-AC-06 C-7 retentionCount 0, 90일: "지금은 종료된 지 N일이 지난 문의가 없습니다."이고 "0건"이나 "옮깁니다"가 없다`: expected '안 읽음 문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 90일이…' to contain '문의 자동 정리가 켜졌습니다. 지금은 종료된 지 90일이 지난 문의…'
- `src/screens/C7Inbox.b2.test.tsx.C-7 자동 정리 켜짐 항목의 문장 (b2 묶음) > B2-AC-06 C-7 retentionCount 0, 180일: "지금은 종료된 지 N일이 지난 문의가 없습니다."이고 "0건"이나 "옮깁니다"가 없다`: expected '안 읽음 문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 180일…' to contain '문의 자동 정리가 켜졌습니다. 지금은 종료된 지 180일이 지난 문…'
- `src/screens/C9CompanyDetail.b2.test.tsx.C-9 소속 고객 표의 익명 고객 (b2 묶음) > B2-AC-04 C-9 익명 고객: 상태 칸 "익명화됨", 이름·이메일은 받은 값, "담당자로 지정"이 없고 "빼기"는 있다`: expected '정지' to be '익명화됨' // Object.is equality
- `src/screens/C9CompanyDetail.b2.test.tsx.C-9 소속 고객 표의 익명 고객 (b2 묶음) > B2-AC-04 C-9 익명이면서 담당자인 고객: 상태 칸 "익명화됨", 담당자 배지, "담당자 해제"가 있다`: expected '정지' to be '익명화됨' // Object.is equality
- `src/screens/C9CompanyDetail.b2.test.tsx.C-9 소속 고객 표의 익명 고객 (b2 묶음) > B2-AC-04 C-9 정지 고객(익명 아님): 지금 그대로 상태 칸 "정지"와 "담당자로 지정". 익명 고객과 한 표에 있어도 status로 익명을 짐작하지 않는다`: expected '정지' to be '익명화됨' // Object.is equality
- `src/screens/C9CompanyDetail.b2.test.tsx.C-9 소속 고객 표의 익명 고객 (b2 묶음) > B2-AC-04 C-9 담당자로 지정이 400 ACCOUNT_ANONYMIZED: 표 밖 띠에 "이미 익명화한 계정입니다."를 보이고 표를 다시 읽어 그 줄이 "익명화됨"이 된다`: 띠 "이미 익명화한 계정입니다.": expected null not to be null  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<head>[39m   
- `src/screens/C9CompanyDetail.b2.test.tsx.C-9 소속 고객 표의 익명 고객 (b2 묶음) > B2-AC-04 C-9 axe (b2):익명 고객, 익명이면서 담당자, 정지 고객이 있는 소속 고객 표에 접근성 위반이 없다`: expected '정지' to be '익명화됨' // Object.is equality
- `src/retention.b2.test.ts.inbox.retentionArmedNone (b2 묶음) > B2-AC-06 ko: 0건 문장은 따로 두고 {days} 자리 하나만 쓴다`: expected undefined to be '문의 자동 정리가 켜졌습니다. 지금은 종료된 지 {days}일이 지…' // Object.is equality
- `src/retention.b2.test.ts.inbox.retentionArmedNone (b2 묶음) > B2-AC-06 en: 0건 문장은 따로 두고 {days} 자리 하나만 쓴다`: expected undefined to be 'Automatic issue cleanup is on. No iss…' // Object.is equality
- `src/retention.b2.test.ts.inbox.retentionArmedNone (b2 묶음) > B2-AC-06 ko: 1건 이상의 inbox.retentionArmed는 그대로이고, 0건 문장도 있다`: 0건 문장: expected undefined to be defined
- `src/retention.b2.test.ts.inbox.retentionArmedNone (b2 묶음) > B2-AC-06 en: 1건 이상의 inbox.retentionArmed는 그대로이고, 0건 문장도 있다`: 0건 문장: expected undefined to be defined
