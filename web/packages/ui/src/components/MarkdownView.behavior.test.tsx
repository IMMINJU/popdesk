// screens.md 5절 24 MarkdownView (COM-6 허용 목록). C-10 변이 테스트가 찾은 빈칸: 블록과 인라인 문법 하나하나.
// 원문 → 그려진 요소를 표로 본다. 링크는 늘 새 탭(rel="noopener noreferrer")이라 아래 표에서는 A(...)로 줄여 쓴다.
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MarkdownView } from './MarkdownView';

const A = (href: string, label: string) => `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`;

function html(source: string): string {
  const { container, unmount } = render(<MarkdownView source={source} />);
  const out = container.firstElementChild?.innerHTML ?? '';
  unmount();
  return out;
}

describe('MarkdownView 인라인', () => {
  test.each([
    ['역슬래시: 기호 앞이면 그 기호를 글자로', 'a\\*b\\* \\_c\\_', '<p>a*b* _c_</p>'],
    ['역슬래시: 글자 앞이나 끝이면 그대로', 'a\\b c\\', '<p>a\\b c\\</p>'],
    ['코드: `로 감싼 글자', 'run `npm ci` now', '<p>run <code>npm ci</code> now</p>'],
    ['코드: 두 겹 `는 안의 `를 담고 앞뒤 빈칸을 뺀다', '`` a`b ``', '<p><code>a`b</code></p>'],
    ['코드: 닫는 `가 없으면 글자', 'a `b', '<p>a `b</p>'],
    ['코드: 안의 마크다운은 그리지 않는다', '`**x**`', '<p><code>**x**</code></p>'],
    ['굵게 **', '**a b**', '<p><strong>a b</strong></p>'],
    ['굵게 __', '__a__', '<p><strong>a</strong></p>'],
    ['취소선 ~~', '~~a~~', '<p><del>a</del></p>'],
    ['굵게: 여는 ** 뒤가 빈칸이면 글자', '** a**', '<p>** a**</p>'],
    ['굵게: 속이 비면 글자', 'a **** b', '<p>a **** b</p>'],
    ['굵게 안의 기울임', '**a _b_**', '<p><strong>a <em>b</em></strong></p>'],
    ['기울임 *', '*a*', '<p><em>a</em></p>'],
    ['기울임 _', '_a_', '<p><em>a</em></p>'],
    ['기울임: 단어 안의 _는 글자(snake_case)', 'snake_case_name', '<p>snake_case_name</p>'],
    ['기울임: 여는 * 뒤가 빈칸이면 글자', 'x * a*', '<p>x * a*</p>'],
    ['기울임: 닫는 * 앞이 빈칸이면 다음 *까지', '*a *b*', '<p><em>a *b</em></p>'],
    ['기울임: _ 뒤에 글자가 붙은 _는 닫는 자리가 아니다', '_a_b c_', '<p><em>a_b c</em></p>'],
    ['기울임: 닫는 기호가 없으면 글자', '*a', '<p>*a</p>'],
    ['기울임: 단어 뒤에 붙은 _로는 열지 않는다', 'a_b_ c', '<p>a_b_ c</p>'],
    ['기울임: 빈칸 뒤의 *는 연다', 'x *a*', '<p>x <em>a</em></p>'],
    ['줄바꿈: 문단 안 줄바꿈은 <br>', 'a\nb', '<p>a<br>b</p>'],
    ['줄바꿈: \\r\\n도 같다', 'a\r\nb\rc', '<p>a<br>b<br>c</p>'],
  ])('MarkdownView %s', (_, source, want) => {
    expect(html(source)).toBe(want);
  });
});

describe('MarkdownView 링크', () => {
  test.each([
    ['기본', '[안내](https://example.com/a)', `<p>${A('https://example.com/a', '안내')}</p>`],
    ['제목은 주소에서 뺀다', '[안내](https://example.com/a "도움말")', `<p>${A('https://example.com/a', '안내')}</p>`],
    ['주소 앞뒤 빈칸', '[안내]( https://example.com/a )', `<p>${A('https://example.com/a', '안내')}</p>`],
    ['주소 안의 짝 맞는 괄호', '[위키](https://w.example/A_(b)) 끝', `<p>${A('https://w.example/A_(b)', '위키')} 끝</p>`],
    ['주소 안의 역슬래시 괄호', '[x](https://a.example/\\)) y', `<p>${A('https://a.example/\\)', 'x')} y</p>`],
    ['글자 안의 짝 맞는 대괄호', '[a [b] c](https://a.example)', `<p>${A('https://a.example', 'a [b] c')}</p>`],
    ['글자 안의 역슬래시 대괄호', '[a \\] b](https://a.example)', `<p>${A('https://a.example', 'a ] b')}</p>`],
    ['글자 안의 서식', '[**굵게**](https://a.example)', `<p>${A('https://a.example', '<strong>굵게</strong>')}</p>`],
    ['mailto', '[메일](mailto:help@example.com)', `<p>${A('mailto:help@example.com', '메일')}</p>`],
    ['대문자 HTTPS', '[x](HTTPS://A.EXAMPLE)', `<p>${A('HTTPS://A.EXAMPLE', 'x')}</p>`],
    ['허용 밖 주소는 글자만 남긴다', '[파일](ftp://a.example/f) 끝', '<p>파일 끝</p>'],
    ['닫는 괄호가 없으면 링크 문법이 아니다(맨 주소만 링크)', '[x](https://a.example', `<p>[x](${A('https://a.example', 'https://a.example')}</p>`],
    ['주소 안에서 줄이 바뀌면 링크 문법이 아니다', '[x](https://a.\nexample)', `<p>[x](${A('https://a', 'https://a')}.<br>example)</p>`],
    ['] 뒤가 (가 아니면 글자', '[x] (https://a.example)', `<p>[x] (${A('https://a.example', 'https://a.example')})</p>`],
    ['닫는 대괄호가 없으면 글자', '[x', '<p>[x</p>'],
    ['<주소>', '<https://a.example/p>', `<p>${A('https://a.example/p', 'https://a.example/p')}</p>`],
    ['<mailto:>', '<mailto:a@b.example>', `<p>${A('mailto:a@b.example', 'mailto:a@b.example')}</p>`],
    ['<허용 밖>은 글자', '<ftp://a.example>', '<p>&lt;ftp://a.example&gt;</p>'],
    ['맨 주소', '보기 https://a.example/x 끝', `<p>보기 ${A('https://a.example/x', 'https://a.example/x')} 끝</p>`],
    ['맨 주소 끝의 문장 부호는 뺀다', 'https://a.example/x).', `<p>${A('https://a.example/x', 'https://a.example/x')}).</p>`],
    ['대문자 맨 주소', 'HTTP://A.EXAMPLE', `<p>${A('HTTP://A.EXAMPLE', 'HTTP://A.EXAMPLE')}</p>`],
    ['단어에 붙은 주소는 링크가 아니다', 'xhttps://a.example', '<p>xhttps://a.example</p>'],
    ['http로 시작해도 주소가 아니면 글자', 'http 설명', '<p>http 설명</p>'],
    ['링크 글자 안의 주소는 링크로 만들지 않는다', '[https://a.example <https://b.example>](https://c.example)', `<p>${A('https://c.example', 'https://a.example &lt;https://b.example&gt;')}</p>`],
    ['링크 글자 안의 [ ]( )는 링크로 만들지 않는다', '[a [b](https://b.example)](https://c.example)', `<p>${A('https://c.example', 'a [b](https://b.example)')}</p>`],
  ])('MarkdownView 링크 %s', (_, source, want) => {
    expect(html(source)).toBe(want);
  });

  test('MarkdownView 링크 javascript:: 링크를 만들지 않고 글자만 남긴다', () => {
    expect(html('[x](javascript:alert(1))')).toBe('<p>x</p>');
  });

  test('MarkdownView 링크 허용 목록은 주소의 맨 앞만 본다: 안에 https://가 들어 있어도 javascript:는 링크가 아니다', () => {
    expect(html("[x](javascript:alert('https://a.example'))")).toBe('<p>x</p>');
    expect(html('[x](data:text/html,mailto:a)')).toBe('<p>x</p>');
  });

  test.each([
    ['http 링크', '[x](http://a.example)', `<p>${A('http://a.example', 'x')}</p>`],
    ['<http 주소>', '<http://a.example>', `<p>${A('http://a.example', 'http://a.example')}</p>`],
    ['<주소>는 < 자리에서만 본다', 'a <b> <https://a.example>', `<p>a &lt;b&gt; ${A('https://a.example', 'https://a.example')}</p>`],
    ['맨 주소는 그 자리에서만 본다', 'http 그리고 https://a.example', `<p>http 그리고 ${A('https://a.example', 'https://a.example')}</p>`],
    ['] 앞에 [가 없으면 링크가 아니다', 'x](https://a.example)', `<p>x](${A('https://a.example', 'https://a.example')})</p>`],
  ])('MarkdownView 링크 자리 %s', (_, source, want) => {
    expect(html(source)).toBe(want);
  });
});

describe('MarkdownView 블록', () => {
  test.each([
    ['빈 줄은 문단을 나눈다', 'a\n\n\nb', '<p>a</p><p>b</p>'],
    ['코드 펜스와 언어', '```js\nlet a = 1;\n```', '<pre tabindex="0"><code data-lang="js">let a = 1;</code></pre>'],
    ['언어 없는 펜스', '```\nx\n```', '<pre tabindex="0"><code>x</code></pre>'],
    ['~~~ 펜스', '~~~\n**x**\n~~~', '<pre tabindex="0"><code>**x**</code></pre>'],
    ['닫히지 않은 펜스는 끝까지', '```\na\n\nb', '<pre tabindex="0"><code>a\n\nb</code></pre>'],
    ['펜스 뒤 문단', '```\na\n```\nb', '<pre tabindex="0"><code>a</code></pre><p>b</p>'],
    ['네 칸 들여쓴 코드', '    a\n\n    b\n\nc', '<pre tabindex="0"><code>a\n\nb</code></pre><p>c</p>'],
    ['탭 들여쓴 코드', '\ta\n\tb', '<pre tabindex="0"><code>a\nb</code></pre>'],
    ['제목 #은 h3부터', '# 하나', '<h3>하나</h3>'],
    ['제목 ##은 h4', '## 둘', '<h4>둘</h4>'],
    ['제목 ####부터는 h6', '#### 넷\n###### 여섯', '<h6>넷</h6><h6>여섯</h6>'],
    ['제목 끝의 #은 뺀다', '## 둘 ##', '<h4>둘</h4>'],
    ['제목 안의 서식', '# **굵게**', '<h3><strong>굵게</strong></h3>'],
    ['#만 붙은 글자는 제목이 아니다', '#태그', '<p>#태그</p>'],
    ['가로줄 ---', 'a\n\n---\n\nb', '<p>a</p><hr><p>b</p>'],
    ['가로줄 * * *', '* * *', '<hr>'],
    ['가로줄 ___', '___', '<hr>'],
    ['--는 가로줄이 아니다', '--', '<p>--</p>'],
    ['인용 여러 줄', '> a\n> b', '<blockquote><p>a<br>b</p></blockquote>'],
    ['인용 안의 블록', '> # 제목\n>\n> - 하나', '<blockquote><h3>제목</h3><ul><li>하나</li></ul></blockquote>'],
    ['인용 뒤 문단', '> a\n\nb', '<blockquote><p>a</p></blockquote><p>b</p>'],
    ['글머리 목록 - * +', '- a\n* b\n+ c', '<ul><li>a</li><li>b</li><li>c</li></ul>'],
    ['글머리 목록', '- a\n- b', '<ul><li>a</li><li>b</li></ul>'],
    ['목록 이어지는 줄', '- a\n  더\n- b', '<ul><li>a<br>더</li><li>b</li></ul>'],
    ['목록 뒤 들여쓰지 않은 줄은 새 문단', '- a\nb', '<ul><li>a</li></ul><p>b</p>'],
    ['번호 목록', '1. a\n2. b', '<ol><li>a</li><li>b</li></ol>'],
    ['번호 목록 1)', '1) a', '<ol><li>a</li></ol>'],
    ['3부터 시작하는 번호 목록', '3. a\n4. b', '<ol start="3"><li>a</li><li>b</li></ol>'],
    ['목록 안의 서식', '- **a**', '<ul><li><strong>a</strong></li></ul>'],
    ['문단 다음 줄이 블록 시작이면 끊는다', 'a\n# 제목', '<p>a</p><h3>제목</h3>'],
    ['문단 다음 줄이 목록이면 끊는다', 'a\n- b', '<p>a</p><ul><li>b</li></ul>'],
    ['문단 다음 줄이 인용이면 끊는다', 'a\n> b', '<p>a</p><blockquote><p>b</p></blockquote>'],
    ['문단 다음 줄이 펜스면 끊는다', 'a\n```\nb\n```', '<p>a</p><pre tabindex="0"><code>b</code></pre>'],
    ['문단 다음 줄이 가로줄이면 끊는다', 'a\n***', '<p>a</p><hr>'],
    ['문단 다음 줄이 번호 목록이면 끊는다', 'a\n1. b', '<p>a</p><ol><li>b</li></ol>'],
    ['빈칸뿐인 줄은 건너뛴다', '   \na', '<p>a</p>'],
    ['줄 가운데 ```는 펜스가 아니다', '설명 ```', '<p>설명 ```</p>'],
    ['네 개 ` 펜스', '````\nx\n````', '<pre tabindex="0"><code>x</code></pre>'],
    ['네 개 ~ 펜스', '~~~~\nx\n~~~~', '<pre tabindex="0"><code>x</code></pre>'],
    ['언어 뒤에 글자가 더 있으면 펜스가 아니다', '```js x', '<p>```js x</p>'],
    ['줄 끝의 ```는 펜스를 닫지 않는다', '```\na```\nb\n```', '<pre tabindex="0"><code>a```\nb</code></pre>'],
    ['들여쓴 닫는 펜스도 닫는다', '```\na\n  ```\nb', '<pre tabindex="0"><code>a</code></pre><p>b</p>'],
    ['제목 # 뒤 빈칸이 여럿이어도 제목 글자만', '#   제목', '<h3>제목</h3>'],
    ['줄 끝의 ---는 가로줄이 아니다', 'a ---', '<p>a ---</p>'],
    ['--- 뒤에 글자가 붙으면 가로줄이 아니다', '---x', '<p>---x</p>'],
    ['--- 뒤에 빈칸과 글자가 있으면 가로줄이 아니다', '--- x', '<p>--- x</p>'],
    ['- 뒤 빈칸이 여럿이어도 목록 글자만', '-  a', '<ul><li>a</li></ul>'],
    ['줄 가운데 1.은 번호 목록이 아니다', 'x 1. y', '<p>x 1. y</p>'],
    ['두 자리 번호', '10. a', '<ol start="10"><li>a</li></ol>'],
    ['번호 뒤 빈칸이 여럿이어도 목록 글자만', '1.  a', '<ol><li>a</li></ol>'],
    ['줄 가운데 네 칸은 코드가 아니다', 'a    b', '<p>a    b</p>'],
    ['코드 뒤 한 칸 들여쓴 줄은 코드가 아니다', '    a\n b', '<pre tabindex="0"><code>a</code></pre><p> b</p>'],
    ['목록 뒤 한 칸 들여쓴 줄은 이어지는 줄이 아니다', '- a\n b', '<ul><li>a</li></ul><p> b</p>'],
  ])('MarkdownView 블록 %s', (_, source, want) => {
    expect(html(source)).toBe(want);
  });
});
