import { Suspense } from "react";
import { MarketsList } from "./MarketsList";

export const metadata = { title: "Markets · HiveBets" };

export default function Page() {
  return (
    <Suspense>
      <MarketsList />
    </Suspense>
  );
}
