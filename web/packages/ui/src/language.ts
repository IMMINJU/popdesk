// The screen language (b5 screens.md 1절). The app keeps it in `<html lang>`; the components
// read it there, so an app that passes no text still gets its language (B5-AC-09). An empty
// `lang`, as when a component is drawn alone, is Korean.

export type PageLocale = 'ko' | 'en';

export function pageLocale(): PageLocale {
  return typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? 'en' : 'ko';
}

/** `ko` or `en` by the screen language. */
export function inLocale<T>(ko: T, en: T): T {
  return pageLocale() === 'en' ? en : ko;
}

/** The components' own default texts, used when the app passes none. */
const KO = {
  write: '쓰기',
  preview: '미리 보기',
  toolbar: '서식',
  image: '이미지',
  emptyPreview: '미리 볼 내용이 없습니다',
  imageDrop: '여기에 놓으면 이미지를 넣습니다',
  imageOnly: '이미지 파일만 본문에 넣을 수 있습니다. 다른 파일은 첨부로 올리세요',
  bold: '굵게',
  italic: '기울임',
  strikethrough: '취소선',
  heading: '제목',
  bulletList: '글머리 목록',
  orderedList: '번호 목록',
  quote: '인용',
  code: '코드',
  link: '링크',
  inlineImage: '본문 이미지',
  clearSearch: '검색어 지우기',
  noLogo: '로고 없음',
  uploading: '올리는 중…',
  imageUnreadable: '이미지를 볼 수 없습니다',
};

export type UiTextKey = keyof typeof KO;

const EN: Record<UiTextKey, string> = {
  write: 'Write',
  preview: 'Preview',
  toolbar: 'Formatting',
  image: 'Image',
  emptyPreview: 'Nothing to preview',
  imageDrop: 'Drop here to add the image',
  imageOnly: 'Only image files go into the text. Attach other files instead',
  bold: 'Bold',
  italic: 'Italic',
  strikethrough: 'Strikethrough',
  heading: 'Heading',
  bulletList: 'Bulleted list',
  orderedList: 'Numbered list',
  quote: 'Quote',
  code: 'Code',
  link: 'Link',
  inlineImage: 'Image in text',
  clearSearch: 'Clear search',
  noLogo: 'No logo',
  uploading: 'Uploading…',
  imageUnreadable: 'The image cannot be shown',
};

export function uiText(key: UiTextKey): string {
  return inLocale(KO[key], EN[key]);
}

/** "이미지 올리는 중… (shot.png)". */
export function imageUploadingText(fileName: string): string {
  return inLocale(`이미지 올리는 중… (${fileName})`, `Uploading image… (${fileName})`);
}

/** "없음. 어두운 테마 로고를 같이 씁니다". */
export function logoFallbackText(from: 'light' | 'dark'): string {
  return inLocale(`없음. ${from === 'light' ? '밝은' : '어두운'} 테마 로고를 같이 씁니다`, `None. The ${from} theme logo is used`);
}
