import type { ComponentType } from 'react';
import { Link, Route, Routes } from 'react-router';
import { P1Login } from './screens/P1Login';
import { P2InviteAccept } from './screens/P2InviteAccept';
import { P3IssueList } from './screens/P3IssueList';
import { P4IssueCreate } from './screens/P4IssueCreate';
import { P5IssueDetail } from './screens/P5IssueDetail';

interface ScreenRoute {
  id: string;
  path: string;
  title: string;
  Screen: ComponentType;
}

// First-slice screens (design/slices/0-first/screens.md). Paths are shells
// until the screen slice; `:` parts are route parameters.
const screens: readonly ScreenRoute[] = [
  { id: 'P-1', path: '/login', title: '로그인', Screen: P1Login },
  { id: 'P-2', path: '/invite', title: '초대 수락', Screen: P2InviteAccept },
  { id: 'P-3', path: '/issues', title: '문의 목록', Screen: P3IssueList },
  { id: 'P-4', path: '/issues/new', title: '문의 등록', Screen: P4IssueCreate },
  { id: 'P-5', path: '/issues/:issueKey', title: '문의 상세', Screen: P5IssueDetail },
];

export function AppRoutes() {
  return (
    <Routes>
      <Route index element={<Home />} />
      {screens.map(({ id, path, Screen }) => (
        <Route key={id} path={path} element={<Screen />} />
      ))}
    </Routes>
  );
}

function Home() {
  return (
    <>
      <h1>Popdesk Portal</h1>
      <ul>
        {screens
          .filter((s) => !s.path.includes(':'))
          .map((s) => (
            <li key={s.id}>
              <Link to={s.path}>
                {s.id} {s.title}
              </Link>
            </li>
          ))}
      </ul>
    </>
  );
}
