// 3번 묶음 screens.md 2절 P-4 문의 등록: 등록 요청의 Idempotency-Key(S3-AC-84). 화면에 보이는 것은 바뀌지 않는다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { atPath, button, callsTo, errors, eventually, field, file, isBlocked, issue, on, renderAt, sent, state, type Call } from '../__tests__/portal';
import { ISSUES } from '../__tests__/portal3';

const title = () => field(/^제목/, 'input');
const content = () => field<HTMLTextAreaElement>(/^내용/, 'textarea');
const attachInput = () => field(/첨부/, 'input[type="file"]');

/** The key of a create request; it must be one the server takes: 1 to 100 visible ASCII characters. */
function keyOf(call: Call | undefined): string {
  const key = call?.headers.get('Idempotency-Key') ?? null;
  expect(key, 'Idempotency-Key 머리글').not.toBeNull();
  expect(key ?? '', 'Idempotency-Key의 꼴').toMatch(/^[\x21-\x7e]{1,100}$/);
  return key ?? '';
}

/** Waits for the `count`th create request and for its failure to reach the form: the button can be pressed again. */
async function created(count: number): Promise<Call | undefined> {
  const calls = await sent('POST', ISSUES, count);
  const send = await button('올리기');
  await eventually(() => {
    expect(isBlocked(send), '답이 온 뒤의 "올리기"').toBe(false);
  });
  return calls[count - 1];
}

describe('P-4 문의 등록 (3번 묶음)', () => {
  test('S3-AC-84 P-4 키: 등록 요청에 Idempotency-Key가 있다. 실패(5xx) 뒤 그대로 다시 보내면 같은 키, 칸을 고치면 다른 키다', async () => {
    let fails = 2;
    on('post', ISSUES, () => {
      if (fails > 0) {
        fails -= 1;
        return errors.internal();
      }
      state.issue = issue({ key: 'ACME-8' });
      return HttpResponse.json(state.issue, { status: 201 });
    });
    const { user } = renderAt('/issues/new');
    await user.type(await title(), '프린터가 안 됩니다');
    await user.type(await content(), '사무실 프린터가 응답하지 않습니다.');

    await user.click(await button('올리기'));
    const first = keyOf(await created(1));
    await user.click(await button('올리기'));
    const again = keyOf(await created(2));
    expect(again, '그대로 다시 보낸 요청의 키').toBe(first);

    await user.type(await title(), ' (3층)');
    await user.click(await button('올리기'));
    const [, , third] = await sent('POST', ISSUES, 3);
    expect(keyOf(third), '칸을 고친 뒤의 키').not.toBe(first);
    await atPath('/issues/ACME-8');
  });

  test('S3-AC-84 P-4 키와 파일: 파일이 있어도 문의를 만드는 요청은 키가 붙은 한 번이다. 파일은 그 뒤에 따로 올리고, 올리다 실패해도 문의를 다시 만들지 않는다', async () => {
    on('post', `${ISSUES}/:key/attachments`, () => errors.internal());
    const { user } = renderAt('/issues/new');
    await user.upload(await attachInput(), [file('a.log'), file('b.log')]);
    await user.type(await title(), '프린터가 안 됩니다');
    await user.click(await button('올리기'));

    await atPath('/issues/ACME-8');
    await sent('POST', `${ISSUES}/ACME-8/attachments`, 2);
    const creates = callsTo('POST', ISSUES);
    expect(creates, '문의를 만드는 요청').toHaveLength(1);
    keyOf(creates[0]);
  });
});
