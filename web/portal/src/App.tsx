import { useState } from 'react';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router';
import { NotFound, PortalLayout } from './app/layout';
import { SessionProvider } from './app/session';
import { ToastProvider } from './app/toast';
import { P1Login } from './screens/P1Login';
import { P2InviteAccept } from './screens/P2InviteAccept';
import { P3IssueList } from './screens/P3IssueList';
import { P4IssueCreate } from './screens/P4IssueCreate';
import { P5IssueDetail } from './screens/P5IssueDetail';

// First-slice screens (design/slices/0-first/screens.md 3절). `/` opens the issue
// list; signed-in screens sit in PortalLayout.
const routes: RouteObject[] = [
  { index: true, element: <Navigate to="/issues" replace /> },
  { path: '/login', element: <P1Login /> },
  { path: '/invite', element: <P2InviteAccept /> },
  {
    element: <PortalLayout />,
    children: [
      { path: '/issues', element: <P3IssueList /> },
      { path: '/issues/new', element: <P4IssueCreate /> },
      { path: '/issues/:issueKey', element: <P5IssueDetail /> },
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
