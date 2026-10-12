"""Find tests whose body can reach no assertion (used by check-test-style.py).

A test passes this check when its body calls an assertion, or calls a function that does. Helper
functions are resolved by name over all test files, transitively, so a body that only calls
`atPath('/issues')` passes when some test helper named `atPath` asserts. That is loose on purpose:
the check is for the test that renders or requests something and then looks at nothing. Whether
an assertion is the right one is for the review step and mutation testing.

Counted as an assertion: assert*(, expect*(, verify*(, fail(, .check(, .andExpect(, Awaitility's
await(), and Testing Library's getBy*/findBy* queries and waitFor* (they throw when nothing
matches). queryBy* is not: it returns null.
"""

import re

ASSERT = re.compile(
    r"\b(assert\w*|expect\w*|verify\w*|fail)\s*\("
    r"|\.(check|verify|andExpect)\s*\("
    r"|\b(find|get)(All)?By\w+\s*\("
    r"|\bwaitFor\w*\s*\("
    r"|\bawait\s*\(\s*\)"
)
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
JAVA_TEST = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
TS_TEST = re.compile(r"\b(?:it|test)(\.each)?\s*(?=[(`])")
TS_FUNCTION = re.compile(r"\bfunction\s+(\w+)\s*(?=[<(])")
TS_CONST = re.compile(r"\b(?:const|let)\s+(\w+)\s*(?::[^=;]+)?=\s*(?:async\s*)?(?=[(<\w])")
NAME_PAREN = re.compile(r"(@?)\b(\w+)\s*\(")
AFTER_PARAMS = re.compile(r"\s*(?:throws\b[^{;]*)?\{")
KEYWORDS = {"if", "for", "while", "switch", "catch", "try", "synchronized", "return", "new", "super", "this"}
PAIRS = {"(": ")", "[": "]", "{": "}", "<": ">"}


def blank(text):
    """The text with comments and the insides of string literals turned into spaces.

    Lengths and line breaks stay, so offsets and line numbers still fit the original. Code inside
    a template literal's `${}` is blanked too.
    """
    out, i, n = list(text), 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
        elif c in "\"'`":
            j = i + 1
            while j < n and text[j] != c and (c == "`" or text[j] != "\n"):
                j += 2 if text[j] == "\\" else 1
            i, j = i + 1, min(j, n)  # keep the quotes
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
            continue
        else:
            i += 1
            continue
        for k in range(i, j):
            if out[k] != "\n":
                out[k] = " "
        i = j
    return "".join(out)


def closing(text, open_at):
    """Index just after the bracket that closes the one at open_at (text is already blanked)."""
    opener, closer = text[open_at], PAIRS[text[open_at]]
    depth = 0
    for j in range(open_at, len(text)):
        if text[j] == opener:
            depth += 1
        elif text[j] == closer:
            if opener == "<" and text[j - 1] == "=":
                continue  # the > of an arrow
            depth -= 1
            if depth == 0:
                return j + 1
    return len(text)


def _java_method_after(text, start):
    """(name, body) of the first method declared at or after start, or None."""
    pos = start
    while True:
        m = NAME_PAREN.search(text, pos)
        if not m:
            return None
        end = closing(text, m.end() - 1)
        if m.group(1) or m.group(2) in KEYWORDS:
            pos = end  # an annotation's arguments
            continue
        brace = AFTER_PARAMS.match(text, end)
        if not brace:
            pos = m.end()
            continue
        return m.group(2), text[brace.end() - 1:closing(text, brace.end() - 1)]


def _java_tests(text):
    for m in JAVA_TEST.finditer(text):
        found = _java_method_after(text, m.end())
        yield m.start(), found[1] if found else None


def _java_defs(text):
    for m in NAME_PAREN.finditer(text):
        if m.group(1) or m.group(2) in KEYWORDS:
            continue
        brace = AFTER_PARAMS.match(text, closing(text, m.end() - 1))
        if brace:
            yield m.group(2), text[brace.end() - 1:closing(text, brace.end() - 1)]


def _ts_tests(text):
    for m in TS_TEST.finditer(text):
        at = m.end()
        if m.group(1):  # .each(table)(title, fn) or .each`table`(title, fn)
            at = closing(text, at) if text[at] == "(" else text.find("`", at + 1) + 1
            while at < len(text) and text[at].isspace():
                at += 1
        if at >= len(text) or text[at] != "(":
            continue
        call = text[at:closing(text, at)]
        arrow, function = call.find("=>"), call.find("function")
        starts = [x for x in (arrow, function) if x >= 0]
        # A callback passed by name is not judged here.
        yield m.start(), call[min(starts):] if starts else None


def _ts_function_body(text, at):
    """Body of the function whose parameters or type parameters start at at."""
    if text[at] == "<":
        at = closing(text, at)
        while at < len(text) and text[at].isspace():
            at += 1
    if at >= len(text):
        return ""
    if text[at] == "(":
        at = closing(text, at)
    arrow = text.find("=>", at)
    brace = text.find("{", at)
    if arrow >= 0 and (brace < 0 or arrow < brace or text[at:arrow].strip().startswith(":")):
        rest = text[arrow + 2:]
        stripped = rest.lstrip()
        if stripped.startswith("{"):
            start = arrow + 2 + len(rest) - len(stripped)
            return text[start:closing(text, start)]
        end = text.find(";", arrow)
        return text[arrow + 2:end if end >= 0 else len(text)]
    return text[brace:closing(text, brace)] if brace >= 0 else ""


def _ts_defs(text):
    for m in TS_FUNCTION.finditer(text):
        yield m.group(1), _ts_function_body(text, m.end())
    for m in TS_CONST.finditer(text):
        at = m.end()
        if text[at] in "(<" or re.match(r"\w+\s*=>", text[at:]):
            yield m.group(1), _ts_function_body(text, at)


def _reaches(body, asserting):
    return bool(ASSERT.search(body)) or not asserting.isdisjoint(CALL.findall(body))


def find(sources):
    """sources: {path: text} of all test files. Returns [(path, line)] of tests with no assertion."""
    blanked = {p: blank(t) for p, t in sources.items()}
    defs = {}
    for p, text in blanked.items():
        for name, body in (_java_defs(text) if p.endswith(".java") else _ts_defs(text)):
            defs.setdefault(name, []).append(body)

    asserting = set()
    grew = True
    while grew:
        grew = False
        for name, bodies in defs.items():
            if name not in asserting and any(_reaches(b, asserting) for b in bodies):
                asserting.add(name)
                grew = True

    flagged = []
    for p, text in blanked.items():
        if p.endswith(".java"):
            tests = _java_tests(text)
        elif ".test." in p or ".spec." in p:
            tests = _ts_tests(text)
        else:
            continue
        for start, body in tests:
            if body is not None and not _reaches(body, asserting):
                flagged.append((p, text.count("\n", 0, start) + 1))
    return flagged
