import { useState } from 'react';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router';
import { NotFound, PortalLayout } from './app/layout';
import { SessionProvider } from './app/session';
import { ToastProvider } from './app/toast';
import { P10PasswordReset } from './screens/P10PasswordReset';
import { P1Login } from './screens/P1Login';
import { P2InviteAccept } from './screens/P2InviteAccept';
import { P3IssueList } from './screens/P3IssueList';
import { P4IssueCreate } from './screens/P4IssueCreate';
import { P5IssueDetail } from './screens/P5IssueDetail';
import { P6MyInfo } from './screens/P6MyInfo';
import { P8Members } from './screens/P8Members';
import { P9PasswordForgot } from './screens/P9PasswordForgot';

// First-slice screens (design/slices/0-first/screens.md 3절). `/` opens the issue
// list; signed-in screens sit in PortalLayout. Slice 1a adds P-9 and P-10 (signed
// out; P-10 takes its token after `#`, like the invitation) and P-6 (signed in).
// Slice 2 adds P-8 (signed in; managers only, which the server's answers decide).
const routes: RouteObject[] = [
  { index: true, element: <Navigate to="/issues" replace /> },
  { path: '/login', element: <P1Login /> },
  { path: '/invite', element: <P2InviteAccept /> },
  { path: '/forgot-password', element: <P9PasswordForgot /> },
  { path: '/reset-password', element: <P10PasswordReset /> },
  {
    element: <PortalLayout />,
    children: [
      { path: '/issues', element: <P3IssueList /> },
      { path: '/issues/new', element: <P4IssueCreate /> },
      { path: '/issues/:issueKey', element: <P5IssueDetail /> },
      { path: '/me', element: <P6MyInfo /> },
      { path: '/members', element: <P8Members /> },
      { path: '*', element: <NotFound listHref="/issues" listLabel="문의 목록으로" /> },
    ],
  },
];

/**
 * The portal app. The router is made when the app mounts, from the address at
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
