// C-13: a day read as 24:00 has no end time of its own. Turning "자정까지" off gives the end field
// 18:00, as a workday turned on does, so the save does not ask for an empty field.
import { expect, test } from 'vitest';
import { eventually, found, renderAt } from '../__tests__/console';
import { URL_SLA, WEEKDAYS, goalTable, midnightBoxNow, onPolicy, s8a, slaTitle, timeFieldsNow } from '../__tests__/console8a';

test('C-13 자정까지를 끄면 끝 칸이 18:00으로 나온다', async () => {
  s8a.policy = onPolicy({ hours: WEEKDAYS.map((d) => ({ weekday: d, opens: d === 'FRIDAY' ? '13:00' : null, closes: d === 'FRIDAY' ? '24:00' : null })) });
  const { user } = renderAt(URL_SLA);
  await slaTitle();
  await goalTable();

  await user.click(await found(() => midnightBoxNow('FRIDAY'), '"금 자정까지"'));

  await eventually(() => {
    expect(timeFieldsNow('FRIDAY').map((f) => f.value)).toEqual(['13:00', '18:00']);
  });
});
