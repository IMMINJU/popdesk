# 실패 확인

- 단언 실패 20, 통과 0, 허용한 통과 1, 오류 0, 건너뜀 0
- 판정: 통과

## 처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)

- `components/ButtonB1.overflow.stories.tsx.Pending Stays`: pending 단추가 지금처럼 커서 progress로 남는지 본다(B1-AC-05 끝, 바뀌지 않아야 할 것)

## 단언에서 실패한 테스트

- `src/screens/C4IssueDetail.b1.test.tsx.C-4 구역의 다시 불러오기 뒤 초점 (B1-AC-02) > B1-AC-02 C-4 이력 읽기 실패에서 "다시 불러오기"를 누르면 초점이 화면 제목 h1(문의 제목)으로 가고 이력 줄이 나온다`: 초점: expected <body><div>…(2)</div></body> to be <h1 class="pd-page-title" …(1)></h1> // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m
- `src/screens/C4IssueDetail.b1.test.tsx.담당자 대화상자의 다시 불러오기 뒤 초점 (B1-AC-03) > B1-AC-03 C-4 담당자 후보 읽기 실패에서 "다시 불러오기"를 누르면 초점이 대화상자 제목 "담당자"로 가고 후보가 나온다`: 초점: expected <body><div>…(2)</div> …(1)</body> to be <h2 id="_r_1d_-title" …(1)></h2> // Object.is equality  Ignored nodes: comments, script, style [36m<html[39
- `src/screens/C5Stats.b1.test.tsx.C-5 다시 불러오기 뒤 초점 (B1-AC-02) > B1-AC-02 C-5 읽기 실패에서 "다시 불러오기"를 누르면 초점이 화면 제목 h1 "통계"로 가고 표가 나온다`: 초점: expected <body><div>…(2)</div></body> to be <h1 class="pd-page-title" …(1)></h1> // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m
- `src/screens/C5Stats.b1.test.tsx.C-5 다시 불러오기 뒤 초점 (B1-AC-02) > B1-AC-02 C-5 다시 읽기도 실패하면 role="alert" 띠가 하나 있고 초점은 h1 "통계"에 남는다`: 초점: expected <button type="button" …(4)>…(1)</button> to be <h1 class="pd-page-title" …(1)></h1> // Object.is equality  Ignored nodes: comments, script, style [
- `src/screens/C7Inbox.b1.test.tsx.C-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 C-7 안 읽은 알림을 열면: 읽음 요청이 끝나기 전에는 수를 다시 묻지 않고, 성공(204)한 뒤 한 번 묻는다`: 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<head>[39m
- `src/screens/C7Inbox.b1.test.tsx.C-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 C-7 읽음 요청이 실패(500)하면 수를 다시 묻지 않고 띠도 없다. 다음 알림의 읽음이 성공하면 그때 한 번 묻는다`: 다음 알림의 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<he
- `src/screens/C7Inbox.b1.test.tsx.C-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 C-7 이미 읽은 알림을 열면 읽음 요청도 다시 묻기도 없다. 안 읽은 알림을 열면 둘 다 있다`: 안 읽은 알림의 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<
- `src/screens/P7Inbox.b1.test.tsx.P-7 다시 불러오기 뒤 초점 (B1-AC-02) > B1-AC-02 P-7 목록 읽기 실패에서 "다시 불러오기"를 누르면 초점이 화면 제목 h1 "알림함"으로 가고 목록이 나온다`: 초점: expected <body><div>…(2)</div></body> to be <h1 class="pd-page-title" …(1)></h1> // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m
- `src/screens/P7Inbox.b1.test.tsx.P-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 P-7 안 읽은 알림을 열면: 읽음 요청이 끝나기 전에는 수를 다시 묻지 않고, 성공(204)한 뒤 한 번 묻는다`: 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<head>[39m
- `src/screens/P7Inbox.b1.test.tsx.P-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 P-7 읽음 요청이 실패(500)하면 수를 다시 묻지 않고 띠도 없다. 다음 알림의 읽음이 성공하면 그때 한 번 묻는다`: 다음 알림의 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<he
- `src/screens/P7Inbox.b1.test.tsx.P-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04) > B1-AC-04 P-7 이미 읽은 알림을 열면 읽음 요청도 다시 묻기도 없다. 안 읽은 알림을 열면 둘 다 있다`: 안 읽은 알림의 읽음이 성공한 뒤: expected 1 to be 2 // Object.is equality  Ignored nodes: comments, script, style [36m<html[39m   [33mlang[39m=[32m"ko"[39m [36m>[39m   [36m<
- `/../../console/src/screens/ScreensB1.overflow.stories.tsx.C 12 Columns 820 Light`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/overflow-b1-콘솔-화면--c-12-columns-820-light&addonPanel=storybook/interactio
- `/../../console/src/screens/ScreensB1.overflow.stories.tsx.C 12 Columns 820 Dark`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/overflow-b1-콘솔-화면--c-12-columns-820-dark&addonPanel=storybook/interaction
- `/../../console/src/screens/ScreensB1.overflow.stories.tsx.C 12 Columns 1280 Light`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/overflow-b1-콘솔-화면--c-12-columns-1280-light&addonPanel=storybook/interacti
- `/../../console/src/screens/ScreensB1.overflow.stories.tsx.C 12 Columns 1280 Dark`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/overflow-b1-콘솔-화면--c-12-columns-1280-dark&addonPanel=storybook/interactio
- `components/ButtonB1.overflow.stories.tsx.Primary Blocked`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/button-b1-막힌-단추--primary-blocked&addonPanel=storybook/interactions/panel[
- `components/ButtonB1.overflow.stories.tsx.Secondary Blocked`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/button-b1-막힌-단추--secondary-blocked&addonPanel=storybook/interactions/pane
- `components/ButtonB1.overflow.stories.tsx.Quiet Blocked`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/button-b1-막힌-단추--quiet-blocked&addonPanel=storybook/interactions/panel[39
- `components/ButtonB1.overflow.stories.tsx.Danger Blocked`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/button-b1-막힌-단추--danger-blocked&addonPanel=storybook/interactions/panel[3
- `components/ButtonB1.overflow.stories.tsx.Internal Blocked`: [34mClick to debug the error directly in Storybook: http://localhost:6006/?path=/story/button-b1-막힌-단추--internal-blocked&addonPanel=storybook/interactions/panel
