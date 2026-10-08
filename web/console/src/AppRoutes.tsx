import type { ComponentType } from 'react';
import { Link, Route, Routes } from 'react-router';
import { C10MemberList } from './screens/C10MemberList';
import { C18SystemStatus } from './screens/C18SystemStatus';
import { C1Login } from './screens/C1Login';
import { C2InviteAccept } from './screens/C2InviteAccept';
import { C3IssueList } from './screens/C3IssueList';
import { C4IssueDetail } from './screens/C4IssueDetail';
import { C8CompanyList } from './screens/C8CompanyList';
import { C9CompanyDetail } from './screens/C9CompanyDetail';

interface ScreenRoute {
  id: string;
  path: string;
  title: string;
  Screen: ComponentType;
}

// First-slice screens (design/slices/0-first/screens.md). Paths are shells
// until the screen slice; `:` parts are route parameters.
const screens: readonly ScreenRoute[] = [
  { id: 'C-1', path: '/login', title: '로그인', Screen: C1Login },
  { id: 'C-2', path: '/invite', title: '초대 수락', Screen: C2InviteAccept },
  { id: 'C-3', path: '/issues', title: '문의 목록', Screen: C3IssueList },
  { id: 'C-4', path: '/issues/:issueKey', title: '문의 상세', Screen: C4IssueDetail },
  { id: 'C-8', path: '/companies', title: '고객사 목록', Screen: C8CompanyList },
  { id: 'C-9', path: '/companies/:code', title: '고객사 상세', Screen: C9CompanyDetail },
  { id: 'C-10', path: '/members', title: '구성원 목록', Screen: C10MemberList },
  { id: 'C-18', path: '/system', title: '시스템 상태', Screen: C18SystemStatus },
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
      <h1>Popdesk Console</h1>
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
