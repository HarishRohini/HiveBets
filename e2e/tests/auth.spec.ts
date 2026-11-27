import { expect, test } from "@playwright/test";
import { adminPage, newTrader, signIn, unique } from "./helpers";

test("a new trader signs up, signs out and signs back in", async ({ browser }) => {
  const { page, username } = await newTrader(browser, "auth");
  await expect(page.getByTestId("nav-username")).toHaveText(username);
  await expect(page.getByTestId("nav-cash")).toContainText("$1,000.00");
  await page.getByTestId("nav-sign-out").click();
  await page.waitForURL(/\/login/);
  await signIn(page, username, "e2e-password-1");
  await expect(page.getByTestId("nav-username")).toHaveText(username);
});

test("a wrong password shows an error and stays on the sign-in page", async ({ page }) => {
  await page.goto("/login");
  await page.getByTestId("login-username").fill(unique("nobody"));
  await page.getByTestId("login-password").fill("wrong-password");
  await page.getByTestId("login-submit").click();
  await expect(page.getByTestId("login-error")).toBeVisible();
  await expect(page).toHaveURL(/\/login/);
});

test("signed-out visitors are sent to sign in", async ({ page }) => {
  await page.goto("/portfolio");
  await expect(page).toHaveURL(/\/login\?next=%2Fportfolio/);
});

test("traders cannot open the admin-only pages", async ({ browser }) => {
  const { page } = await newTrader(browser, "deny");
  await expect(page.getByTestId("nav-analytics")).toHaveCount(0);
  await expect(page.getByTestId("nav-admin")).toHaveCount(0);
  await page.goto("/analytics");
  await expect(page).toHaveURL(/\/markets\?denied=analytics/);
  await expect(page.getByTestId("denied-banner")).toBeVisible();
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/markets\?denied=admin/);
});

test("the admin sees Analytics with data freshness and KPI tiles", async ({ browser }) => {
  const admin = await adminPage(browser);
  await admin.getByTestId("nav-analytics").click();
  await expect(admin).toHaveURL(/\/analytics/);
  await expect(admin.getByTestId("data-as-of")).toBeVisible();
  for (const kpi of ["kpi-resolved", "kpi-trades", "kpi-volume", "kpi-orders", "kpi-sessions"]) {
    await expect(admin.getByTestId(kpi)).toBeVisible();
  }
});
