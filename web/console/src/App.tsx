import { useState } from 'react';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router';
import { AdminOnly, ConsoleLayout, NotFound } from './app/layout';
import { SessionProvider } from './app/session';
import { ToastProvider } from './app/toast';
import { C10MemberList } from './screens/C10MemberList';
import { C18SystemStatus } from './screens/C18SystemStatus';
import { C1Login } from './screens/C1Login';
import { C2InviteAccept } from './screens/C2InviteAccept';
import { C3IssueList } from './screens/C3IssueList';
import { C4IssueDetail } from './screens/C4IssueDetail';
import { C8CompanyList } from './screens/C8CompanyList';
import { C9CompanyDetail } from './screens/C9CompanyDetail';

// First-slice screens (design/slices/0-first/screens.md 4절). `/` opens the issue
// list; signed-in screens sit in ConsoleLayout, admin screens also in AdminOnly.
const routes: RouteObject[] = [
  { index: true, element: <Navigate to="/issues" replace /> },
  { path: '/login', element: <C1Login /> },
  { path: '/invite', element: <C2InviteAccept /> },
  {
    element: <ConsoleLayout />,
    children: [
      { path: '/issues', element: <C3IssueList /> },
      { path: '/issues/:issueKey', element: <C4IssueDetail /> },
      { path: '/companies', element: <AdminOnly><C8CompanyList /></AdminOnly> },
      { path: '/companies/:code', element: <AdminOnly><C9CompanyDetail /></AdminOnly> },
      { path: '/members', element: <AdminOnly><C10MemberList /></AdminOnly> },
      { path: '/system', element: <AdminOnly><C18SystemStatus /></AdminOnly> },
      { path: '*', element: <NotFound listHref="/issues" listLabel="문의 목록으로" /> },
    ],
  },
];

/**
 * The console app. The router is made when the app mounts, from the address at
 * that time; the toast stack and the session live above it so both survive moves.
 */
export function App() {
  const [router] = useState(() => createBrowserRouter(routes));
  return (
    <SessionProvider>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </SessionProvider>
  );
}
