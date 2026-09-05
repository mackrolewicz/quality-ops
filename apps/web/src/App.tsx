import { Suspense } from "react";

import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";

import { queryClient } from "./lib/queryClient";
import { PageSkeleton } from "./components/PageSkeleton";
import { AuthProvider } from "./features/auth/AuthProvider";
import { router } from "./router";

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Suspense fallback={<PageSkeleton />}>
          <RouterProvider router={router} />
        </Suspense>
      </AuthProvider>
    </QueryClientProvider>
  );
}
