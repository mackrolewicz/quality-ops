/* eslint-disable react-refresh/only-export-components -- route module intentionally exports the router config */
import { lazy } from "react";

import { createBrowserRouter, Navigate } from "react-router-dom";

import { EmptyState } from "./components/EmptyState";
import { AppLayout } from "./layouts/AppLayout";
import { RequireAuth } from "./features/auth/RequireAuth";

const LoginPage = lazy(() =>
  import("./features/auth/LoginPage").then((m) => ({ default: m.LoginPage })),
);
const DashboardPage = lazy(() =>
  import("./features/dashboard/DashboardPage").then((m) => ({
    default: m.DashboardPage,
  })),
);
const ProjectsPage = lazy(() =>
  import("./features/projects/ProjectsPage").then((m) => ({
    default: m.ProjectsPage,
  })),
);
const ProjectDetailPage = lazy(() =>
  import("./features/projects/ProjectDetailPage").then((m) => ({
    default: m.ProjectDetailPage,
  })),
);
const SuiteDetailPage = lazy(() =>
  import("./features/suites/SuiteDetailPage").then((m) => ({
    default: m.SuiteDetailPage,
  })),
);
const RunsPage = lazy(() =>
  import("./features/runs/RunsPage").then((m) => ({ default: m.RunsPage })),
);
const RunDetailPage = lazy(() =>
  import("./features/runs/RunDetailPage").then((m) => ({
    default: m.RunDetailPage,
  })),
);
const SettingsPage = lazy(() =>
  import("./features/settings/SettingsPage").then((m) => ({
    default: m.SettingsPage,
  })),
);

export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    path: "/",
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          {
            index: true,
            element: <DashboardPage />,
            handle: { title: "Dashboard" },
          },
          {
            path: "projects",
            element: <ProjectsPage />,
            handle: { title: "Projects" },
          },
          {
            path: "projects/:projectId",
            element: <ProjectDetailPage />,
            handle: { title: "Project" },
          },
          {
            path: "suites/:suiteId",
            element: <SuiteDetailPage />,
            handle: { title: "Test Suite" },
          },
          {
            path: "suites",
            handle: { title: "Test Suites" },
            element: (
              <EmptyState
                title="Choose a project to view its suites"
                description="Test suites live inside a project."
                action={
                  <a
                    href="/projects"
                    className="text-sm text-accent hover:underline"
                  >
                    Go to Projects
                  </a>
                }
              />
            ),
          },
          {
            path: "runs",
            element: <RunsPage />,
            handle: { title: "Runs" },
          },
          {
            path: "runs/:runId",
            element: <RunDetailPage />,
            handle: { title: "Run" },
          },
          {
            path: "results",
            element: <Navigate to="/runs" replace />,
          },
          {
            path: "settings",
            element: <SettingsPage />,
            handle: { title: "Settings" },
          },
          {
            path: "*",
            handle: { title: "Not found" },
            element: (
              <EmptyState
                title="Page not found"
                description="The page you are looking for does not exist."
                action={
                  <a href="/" className="text-sm text-accent hover:underline">
                    Back to Dashboard
                  </a>
                }
              />
            ),
          },
        ],
      },
    ],
  },
]);
