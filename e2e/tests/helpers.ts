import { expect, type Browser, type Page } from "@playwright/test";

export const API_URL = (process.env.E2E_API_URL ?? "http://localhost:8080").replace(/\/$/, "");
export const ADMIN = {
  username: process.env.ADMIN_USERNAME ?? "admin",
  password: process.env.ADMIN_PASSWORD ?? "change-me-admin",
};

/** Short unique suffix so tests never collide with each other or with earlier runs. */
export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

export async function signIn(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByTestId("tab-sign-in").click();
  await page.getByTestId("login-username").fill(username);
  await page.getByTestId("login-password").fill(password);
  await page.getByTestId("login-submit").click();
  await page.waitForURL(/\/markets/);
}

export async function signUp(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByTestId("tab-sign-up").click();
  await page.getByTestId("login-username").fill(username);
  await page.getByTestId("login-password").fill(password);
  await page.getByTestId("login-submit").click();
  await page.waitForURL(/\/markets/);
}

/** A new browser context (own cookies) signed in as a fresh trader. */
export async function newTrader(browser: Browser, prefix = "e2e") {
  const page = await (await browser.newContext()).newPage();
  const username = unique(prefix);
  await signUp(page, username, "e2e-password-1");
  return { page, username };
}

export async function adminPage(browser: Browser) {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, ADMIN.username, ADMIN.password);
  return page;
}

/** Creates a market on the Admin screen and returns its id and question. */
export async function createMarket(admin: Page, minutes = 10) {
  const question = `E2E ${unique("q")}: will this test pass?`;
  await admin.goto("/admin");
  await admin.getByTestId("create-question").fill(question);
  await admin.getByTestId("create-minutes").fill(String(minutes));
  await admin.getByTestId("create-submit").click();
  await expect(admin.getByTestId("create-done")).toBeVisible();
  const row = admin.getByTestId("admin-market-row").filter({ hasText: question });
  await expect(row).toBeVisible();
  const id = await row.getAttribute("data-market-id");
  expect(id).toBeTruthy();
  return { id: id!, question };
}

/** Fills and submits the order ticket on an open market page; returns the toast text. */
export async function placeOrder(page: Page, o: { side: "YES" | "NO"; action?: "BUY" | "SELL"; price: number; shares?: number }) {
  await page.getByTestId(o.action === "SELL" ? "ticket-action-sell" : "ticket-action-buy").click();
  await page.getByTestId(o.side === "YES" ? "ticket-side-yes" : "ticket-side-no").click();
  await page.getByTestId("ticket-shares").fill(String(o.shares ?? 10));
  await page.getByTestId("ticket-price").fill(String(o.price));
  await page.getByTestId("ticket-submit").click();
  const toast = page.getByTestId("ticket-toast").filter({ hasNotText: "No order yet" });
  await expect(toast).toBeVisible();
  return (await toast.innerText()).trim();
}
