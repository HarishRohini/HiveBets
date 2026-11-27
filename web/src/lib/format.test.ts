import { describe, expect, it } from "vitest";
import { cents, countdown, money, moneyShort } from "./format";

describe("money", () => {
  it("formats cents as dollars", () => {
    expect(money(104260)).toBe("$1,042.60");
    expect(money(0)).toBe("$0.00");
    expect(money(5)).toBe("$0.05");
  });
  it("signs gains and losses", () => {
    expect(money(12900, { sign: true })).toBe("+$129.00");
    expect(money(-180, { sign: true })).toBe("-$1.80");
    expect(money(-180)).toBe("-$1.80");
  });
  it("handles missing values", () => {
    expect(money(null)).toBe("—");
    expect(money(undefined)).toBe("—");
  });
  it("rounds whole dollars for tiles", () => {
    expect(moneyShort(1842049)).toBe("$18,420");
  });
});

describe("cents and countdown", () => {
  it("formats prices", () => {
    expect(cents(63)).toBe("63¢");
    expect(cents(null)).toBe("—");
  });
  it("counts down", () => {
    expect(countdown(252_000)).toBe("04:12");
    expect(countdown(0)).toBe("00:00");
    expect(countdown(3 * 3600_000)).toBe("3 h");
    expect(countdown(3 * 86400_000)).toBe("3 days");
  });
});
