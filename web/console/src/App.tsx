import { useState } from 'react';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router';
import { MY_ISSUES_HREF } from './app/issueFilters';
import { AdminOnly, ConsoleLayout, NotFound } from './app/layout';
import { SessionProvider } from './app/session';
import { ToastProvider } from './app/toast';
import { C10MemberList } from './screens/C10MemberList';
import { C11Trash } from './screens/C11Trash';
import { C12AuditLog } from './screens/C12AuditLog';
import { C13SlaPolicy } from './screens/C13SlaPolicy';
import { C15MyTasks } from './screens/C15MyTasks';
import { C16PasswordForgot } from './screens/C16PasswordForgot';
import { C17PasswordReset } from './screens/C17PasswordReset';
import { C18SystemStatus } from './screens/C18SystemStatus';
import { C19IssueCreate } from './screens/C19IssueCreate';
import { C1Login } from './screens/C1Login';
import { C2InviteAccept } from './screens/C2InviteAccept';
import { C3IssueList } from './screens/C3IssueList';
import { C4IssueDetail } from './screens/C4IssueDetail';
import { C5Stats } from './screens/C5Stats';
import { C6MyInfo } from './screens/C6MyInfo';
import { C7Inbox } from './screens/C7Inbox';
import { C8CompanyList } from './screens/C8CompanyList';
import { C9CompanyDetail } from './screens/C9CompanyDetail';

// First-slice screens (design/slices/0-first/screens.md 4절). `/` opens the issue
// list on 내 담당 (slice 3); signed-in screens sit in ConsoleLayout, admin screens also in AdminOnly.
// Slice 1a adds C-16 and C-17 (signed out; C-17 takes its token after `#`, like
// the invitation) and C-6 (signed in, every role).
// Slice 1b adds C-12 (admin only).
// Slice 3 adds C-19 (every role), before `/issues/:issueKey` so `new` is not read as a key.
// Slice 4 adds C-7 (every role).
// Slice 6b adds C-15 (every role).
// Slice 7a adds C-11 (admin only).
// Slice 7b adds C-5 (every role).
// Slice 8a adds C-13 (admin only).
const routes: RouteObject[] = [
  { index: true, element: <Navigate to={MY_ISSUES_HREF} replace /> },
  { path: '/login', element: <C1Login /> },
  { path: '/invite', element: <C2InviteAccept /> },
  { path: '/forgot-password', element: <C16PasswordForgot /> },
  { path: '/reset-password', element: <C17PasswordReset /> },
  {
    element: <ConsoleLayout />,
    children: [
      { path: '/issues', element: <C3IssueList /> },
      { path: '/issues/new', element: <C19IssueCreate /> },
      { path: '/issues/:issueKey', element: <C4IssueDetail /> },
      { path: '/tasks', element: <C15MyTasks /> },
      { path: '/stats', element: <C5Stats /> },
      { path: '/me', element: <C6MyInfo /> },
      { path: '/inbox', element: <C7Inbox /> },
      { path: '/companies', element: <AdminOnly><C8CompanyList /></AdminOnly> },
      { path: '/companies/:code', element: <AdminOnly><C9CompanyDetail /></AdminOnly> },
      { path: '/members', element: <AdminOnly><C10MemberList /></AdminOnly> },
      { path: '/trash', element: <AdminOnly><C11Trash /></AdminOnly> },
      { path: '/sla', element: <AdminOnly><C13SlaPolicy /></AdminOnly> },
      { path: '/audit-log', element: <AdminOnly><C12AuditLog /></AdminOnly> },
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
