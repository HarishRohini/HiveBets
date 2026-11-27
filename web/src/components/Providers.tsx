"use client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { startClickstream, track } from "@/lib/clickstream";

function PageViews() {
  const path = usePathname();
  useEffect(() => {
    startClickstream();
  }, []);
  useEffect(() => {
    track("page_view", { title: typeof document !== "undefined" ? document.title : undefined });
  }, [path]);
  return null;
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 2000 } },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      <PageViews />
      {children}
    </QueryClientProvider>
  );
}
