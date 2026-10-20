# b6 실패 확인 (C-6)

> 테스트 탭 `sb6-t`, 커밋 `5035862`. 메인이 2026-10-20에 확인했다.

- `check-red.py`: 단언 실패 23, 허용한 통과 11(`red-allow.txt`), 오류 0, 판정 통과.
- 무작위 순서 세 번(`--sequence.shuffle`): 모두 5 통과, 9 실패, 실패 목록이 같다.
- 앞 묶음: i18n·console·portal 전체와 story 실행기 전체에서 새 기준 때문에 빨간 것 말고는 모두 통과.
- typecheck, lint, dup, `check-test-style.py`, `check-locked-paths.py --role tests` 통과.

## 실패 까닭(구현 전)

- C-9 820 한국어: 표 틀 749 > 708, "삭제된 사용자" 2줄. 영어 820·1280: 1015 > 708 / 920.
- C-11 820 한국어: 836 > 708. 영어: 1033 > 708(820), 1033 > 1008(1280).
- P-3 영어 1280·820: 체크가 셋째 줄 첫 칸이고 앞 줄과의 틈이 36px(줄 사이 틈 12px).
- 자동 정리 영어: 단수가 없고 머리가 다르다. 콘솔 `index.html` 제목이 한국어다.

## 고친 잠긴 파일

- `retention.b2.test.ts`의 영어 두 줄(`acceptance.md` 3절 5). 한국어 줄은 그대로.
- `packages/ui/src/__tests__/overflow.ts`에 b1의 `linesOf`를 옮겼다(`.pd-sr-only` 글을 빼는 선택 인자를 더함). `ScreensB1.overflow.stories.tsx`는 그것을 부른다. b1 story 4개는 그대로 통과.

## 메인이 정한 것

- 1280 한국어 C-9는 이 견본으로 이미 통과한다(b2 때 본 두 줄은 다른 견본이었다). 같은 규칙을 영어 1280이 실패로 보므로 견본을 더하지 않았다.
- 포털 `indexHtml.b6`는 처음부터 통과해 `red-allow.txt`에도 두었다. 같은 이름의 콘솔 파일과 함께 pending 글롭에 걸린다.
