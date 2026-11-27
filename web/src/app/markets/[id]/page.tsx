import { MarketView } from "./MarketView";

export const metadata = { title: "Market · HiveBets" };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <MarketView id={id} />;
}
