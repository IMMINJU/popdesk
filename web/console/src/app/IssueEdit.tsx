// C-4 고치기 (3-issues screens.md 2절, S3-AC-81): the shared form with the console's
// PATCH and the agent's words for the status.
import { IssueEdit as IssueEditWith, type IssueEditBody, type IssueEditProps as SharedProps } from '@popdesk/app/IssueEdit';
import { api, type Schemas } from '../api';
import { imagePaths } from './attachments';
import { call } from './result';

type Issue = Schemas['Issue'];

export type IssueEditProps = Omit<SharedProps<Issue>, 'save' | 'audience'>;

const save = (key: string, body: IssueEditBody) => call(api.PATCH('/api/console/issues/{key}', { params: { path: { key } }, body }));

export function IssueEdit(props: IssueEditProps) {
  return <IssueEditWith {...props} save={save} audience="agent" images={imagePaths(props.issue.key)} />;
}
