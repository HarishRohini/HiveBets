import { expect, test } from "@playwright/test";
import { adminPage, createMarket, newTrader, placeOrder } from "./helpers";

test("a new market and its headline appear live for a trader", async ({ browser }) => {
  const admin = await adminPage(browser);
  const { page: trader } = await newTrader(browser, "live");
  await trader.goto("/markets");
  await expect(trader.getByTestId("market-card").first()).toBeVisible();

  const m = await createMarket(admin);
  // No reload: the markets list picks the new market up from the live feed.
  const card = trader.getByTestId("market-card").filter({ hasText: m.question });
  await expect(card).toBeVisible();
  await expect(card.getByTestId("market-kind")).toHaveText(/Simulated/);
  await card.getByTestId("market-open").click();
  await trader.waitForURL(new RegExp(`/markets/${m.id}`));
  await expect(trader.getByTestId("market-question")).toHaveText(m.question);

  const text = `E2E headline ${Date.now()}: inspectors arrive early`;
  await admin.goto("/admin");
  await admin.getByTestId("headline-market").selectOption(m.id);
  await admin.getByTestId("headline-input").fill(text);
  await admin.getByTestId("headline-submit").click();
  await expect(admin.getByTestId("headline-done")).toBeVisible();
  await expect(trader.getByTestId("headline").filter({ hasText: text })).toBeVisible();
});

test("crossing orders trade between two accounts, then resolution pays the winner", async ({ browser }) => {
  const admin = await adminPage(browser);
  const m = await createMarket(admin);
  const { page: maker } = await newTrader(browser, "maker");
  const { page: taker, username: takerName } = await newTrader(browser, "taker");
  await maker.goto(`/markets/${m.id}`);
  await taker.goto(`/markets/${m.id}`);

  // Maker rests a NO bid at 40 (a YES ask at 60).
  expect(await placeOrder(maker, { side: "NO", price: 40, shares: 10 })).toMatch(/^Order placed: buy 10 No at 40¢/);
  await expect(maker.getByTestId("order-row")).toHaveCount(1);
  await expect(taker.getByTestId("best-ask")).toContainText("60");

  // Taker buys YES at 60 and crosses it.
  expect(await placeOrder(taker, { side: "YES", price: 60, shares: 10 })).toBe("Filled: bought 10 Yes at 60¢.");
  await expect(taker.getByTestId("position")).toHaveText("10 Yes");
  await expect(maker.getByTestId("position")).toHaveText("10 No");
  // The maker's page shows the trade live, by the taker.
  await expect(maker.getByTestId("trade-row").first()).toContainText(takerName);
  await expect(maker.getByTestId("trade-row").first()).toContainText("10 Yes @ 60¢");
  await expect(maker.getByTestId("order-row")).toHaveCount(0);
  await expect(taker.getByTestId("yes-price")).toContainText("60");

  // Resolve YES: the taker's 10 shares pay $10.00, the maker's NO shares pay nothing.
  await admin.goto("/admin");
  const row = admin.getByTestId("admin-market-row").filter({ hasText: m.question });
  await row.getByTestId("resolve-yes").click();
  await admin.getByTestId("resolve-confirm").click();
  await expect(taker.getByTestId("resolved-banner")).toContainText("Your winning shares paid out $10.00.");
  await expect(maker.getByTestId("resolved-banner")).toContainText("losing side");
  await expect(taker.getByTestId("ticket-submit")).toBeDisabled();

  await taker.goto("/portfolio");
  await expect(taker.getByTestId("wallet-equity")).toHaveText("$1,004.00");
  await expect(taker.getByTestId("wallet-profit")).toHaveText("+$4.00");
  await expect(taker.getByTestId("resolved-holding").filter({ hasText: m.question })).toBeVisible();

  await maker.goto("/leaderboard");
  await expect(maker.getByTestId("my-rank")).toBeVisible();
  await expect(maker.getByTestId("leader-row").filter({ hasText: takerName })).toBeVisible();
});

test("an open order can be cancelled and its cash is released", async ({ browser }) => {
  const admin = await adminPage(browser);
  const m = await createMarket(admin);
  const { page } = await newTrader(browser, "cancel");
  await page.goto(`/markets/${m.id}`);
  await expect(page.getByTestId("ticket-available")).toHaveText("$1,000.00");
  await placeOrder(page, { side: "YES", price: 20, shares: 10 });
  await expect(page.getByTestId("order-row")).toHaveCount(1);
  await expect(page.getByTestId("ticket-available")).toHaveText("$998.00");
  await expect(page.getByTestId("best-bid")).toContainText("20");

  await page.getByTestId("order-row").getByTestId("order-cancel").click();
  await expect(page.getByTestId("order-row")).toHaveCount(0);
  await expect(page.getByTestId("ticket-available")).toHaveText("$1,000.00");
});

test("buying more than you can afford is blocked in the ticket", async ({ browser }) => {
  const admin = await adminPage(browser);
  const m = await createMarket(admin);
  const { page } = await newTrader(browser, "broke");
  await page.goto(`/markets/${m.id}`);
  await page.getByTestId("ticket-shares").fill("5000");
  await page.getByTestId("ticket-price").fill("99");
  await expect(page.getByTestId("ticket-submit")).toHaveText("Not enough funds");
  await expect(page.getByTestId("ticket-submit")).toBeDisabled();
});
