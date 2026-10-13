// P-5 고치기 (3-issues screens.md 2절, S3-AC-81): the shared form with the portal's
// PATCH and the customer's words for the status. The form shows only what the
// customer sees of an issue: no assignee, channel or history.
import { IssueEdit as IssueEditWith, type IssueEditBody, type IssueEditProps as SharedProps } from '@popdesk/app/IssueEdit';
import { api, type Schemas } from '../api';
import { call } from './result';

type Issue = Schemas['Issue'];

export type IssueEditProps = Omit<SharedProps<Issue>, 'save' | 'audience'>;

const save = (key: string, body: IssueEditBody) => call(api.PATCH('/api/portal/issues/{key}', { params: { path: { key } }, body }));

export function IssueEdit(props: IssueEditProps) {
  return <IssueEditWith {...props} save={save} audience="customer" />;
}
