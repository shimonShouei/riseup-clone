import { describe, expect, it } from "vitest";
import {
  CSV_HEADER,
  countTransactions,
  csvQuote,
  formatDate,
  resultToCsv,
  splitAmount,
  transactionToRow,
} from "../src/csv.js";
import type { LibraryResult } from "../src/types.js";

/**
 * A representative `israeli-bank-scrapers` success result. Mirrors the shape the
 * library returns so these tests need no bank, no credentials, and no Chrome.
 * The two txns exercise both signs, a comma in the description (must be quoted),
 * a numeric identifier, and a missing identifier.
 */
const librarySuccess: LibraryResult = {
  success: true,
  accounts: [
    {
      accountNumber: "12-345-6789",
      cardType: "bankIssued",
      balance: 15234.56,
      txns: [
        {
          identifier: 987654,
          date: "2026-01-15T00:00:00.000Z",
          chargedAmount: -50.25,
          description: "SUPER PHARM, TEL AVIV",
          category: "בריאות",
        },
        {
          // no identifier
          date: "2026-02-01T00:00:00.000Z",
          chargedAmount: 14000,
          description: "MASKORET HEVRAT HI-TECH",
        },
      ],
    },
  ],
};

describe("formatDate", () => {
  it("reformats an ISO date to day-first dd/MM/yyyy (UTC)", () => {
    expect(formatDate("2026-01-15T00:00:00.000Z")).toBe("15/01/2026");
    expect(formatDate("2026-12-05T22:30:00.000Z")).toBe("05/12/2026");
  });

  it("throws on an unparseable date", () => {
    expect(() => formatDate("not-a-date")).toThrow();
  });
});

describe("splitAmount", () => {
  it("puts a negative charge in Debit (unsigned, 2dp) and leaves Credit blank", () => {
    expect(splitAmount(-50.25)).toEqual({ debit: "50.25", credit: "" });
  });

  it("puts a positive charge in Credit (unsigned, 2dp) and leaves Debit blank", () => {
    expect(splitAmount(14000)).toEqual({ debit: "", credit: "14000.00" });
  });

  it("treats zero as Credit", () => {
    expect(splitAmount(0)).toEqual({ debit: "", credit: "0.00" });
  });
});

describe("csvQuote (RFC-4180)", () => {
  it("leaves plain values untouched", () => {
    expect(csvQuote("SALARY")).toBe("SALARY");
  });

  it("quotes and preserves values containing a comma", () => {
    expect(csvQuote("SUPER PHARM, TEL AVIV")).toBe('"SUPER PHARM, TEL AVIV"');
  });

  it("doubles embedded quotes", () => {
    expect(csvQuote('A "B" C')).toBe('"A ""B"" C"');
  });
});

describe("transactionToRow", () => {
  it("builds the exact 6-column row: debit txn with a quoted description + reference", () => {
    const txn = librarySuccess.accounts![0]!.txns![0]!;
    expect(transactionToRow(txn)).toBe('15/01/2026,"SUPER PHARM, TEL AVIV",50.25,,,987654');
  });

  it("splits a credit txn into the Credit column and passes an empty reference through", () => {
    const txn = librarySuccess.accounts![0]!.txns![1]!;
    expect(transactionToRow(txn)).toBe("01/02/2026,MASKORET HEVRAT HI-TECH,,14000.00,,");
  });

  it("emits the balance cell only when a balance is supplied", () => {
    const txn = librarySuccess.accounts![0]!.txns![1]!;
    expect(transactionToRow(txn, 15234.56)).toBe("01/02/2026,MASKORET HEVRAT HI-TECH,,14000.00,15234.56,");
  });
});

describe("resultToCsv", () => {
  it("emits the fixed header and one row per transaction, in order", () => {
    const csv = resultToCsv(librarySuccess);
    const lines = csv.split("\r\n").filter((l) => l.length > 0);
    expect(lines[0]).toBe(CSV_HEADER);
    expect(lines).toHaveLength(3);
    expect(lines[1]).toBe('15/01/2026,"SUPER PHARM, TEL AVIV",50.25,,,987654');
    // The account balance is attached to the LAST row only.
    expect(lines[2]).toBe("01/02/2026,MASKORET HEVRAT HI-TECH,,14000.00,15234.56,");
  });

  it("does NOT emit a Category column (categories default to Other in the app)", () => {
    expect(CSV_HEADER).toBe("Date,Description,Debit,Credit,Balance,Reference");
    expect(resultToCsv(librarySuccess)).not.toContain("בריאות");
  });

  it("terminates the document with a trailing newline", () => {
    expect(resultToCsv(librarySuccess).endsWith("\r\n")).toBe(true);
  });

  it("handles an empty result (header only)", () => {
    const csv = resultToCsv({ success: true, accounts: [] });
    expect(csv).toBe(`${CSV_HEADER}\r\n`);
  });
});

describe("countTransactions", () => {
  it("sums transactions across accounts", () => {
    expect(countTransactions(librarySuccess)).toBe(2);
    expect(countTransactions({ success: true, accounts: [] })).toBe(0);
  });
});
