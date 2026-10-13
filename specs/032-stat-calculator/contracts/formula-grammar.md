# Contract C1: Formula grammar

This grammar is the whole language. It has no functions, no variables and no implicit
multiplication.

```text
expr    := term   (('+' | '-') term)*
term    := unary  (('*' | '/' | '×' | '÷') unary)*
unary   := '-' unary | primary
primary := NUMBER | STAT | '(' expr ')'

NUMBER  := digits ('.' digits)?          e.g. 100, 0.5
STAT    := a column id or formula token, case-insensitive, from the input set (data-model "Stat input")
```

## Lexing rules

1. Whitespace is ignored.
2. At each position, the lexer first tries the **longest STAT match** against all ids and
   tokens, and falls back to NUMBER only when no STAT matches. This is what makes `3PM`, `3P%`
   and `3PAr` lex as stats rather than as `3` followed by junk (research R3).
2a. *(Amended after plan review, F1)* A STAT match counts only at a **token boundary**: the next
   character must not be a letter, a digit or `%`. Otherwise the next-longest match is tried. No
   token contains an operator, bracket or space (`FP/G` is `FPG`, `Pos rank` is `POSRANK`, and
   `Pts rank` is `PTSRANK`), so `fp/gp` lexes as `fp`, `/`, `gp`.
3. `−` (U+2212) is accepted as `-`, and `×` and `÷` as `*` and `/`, so a formula copied from the
   readable display parses back.
4. Any other character is an error at its position.

## Errors

Each error has a message in words and a character span. Every one of these is required:

| Input | Message |
|---|---|
| `(usg + vor` | "Bracket opened at 1 is never closed" |
| `usg + * tov` | "Two operators in a row at 7" |
| `2usg` | "Put an operator between 2 and USG" |
| `usg + foo` | "‘foo’ isn't a stat. Pick one from the list" |
| `` (empty) | "Enter a formula" |
| `usg +` | "The formula ends after an operator" |
| `.5` | "Numbers need a leading digit: write 0.5" (N4) |
| `1e3` | "Write 1000; exponents aren't supported" (N4) |
| `1.5.5` | "‘1.5.5’ has two decimal points; a number can have only one" *(amended after code review, B8)* |
| a number too large for a number (400 digits) | "That number is too large" *(amended after code review, B3)* |
| `3PA` (a number run into letters that are not a stat) | "‘3PA’ isn't a stat or a number. Pick a stat from the list" *(amended after code review, B8)* |
| `FP/G`, `Pos rank`, `Pts rank` (the table's old labels) | "‘FP/G’ is how the table prints it. In a formula, write FPG" *(amended after code review, B8; the calculator's own headers and axes now print the token too)* |
| a formula with no stat (`1 + 1`) | "Use at least one stat. A formula made only of numbers gives every player the same result" *(amended after code review, B3; both modes)* |
| `usg / 0`, `usg / (2 - 2)` (a constant denominator equal to 0) | "Dividing by 0 is undefined" *(amended after code review, B3; both modes)* |
| `pts * ts` with standardize on (a `×` with a stat on both sides) | "With standardize on, multiplying two stats isn't meaningful: a player who is below average on both gets a large positive result, because two negatives multiply to a positive. Turn standardize off, multiply by a number instead, or rewrite with + and −." The span covers the `×` and its right side *(amended after code review, B1)* |
| `usg / tov` with standardize on | "With standardize on, dividing by a stat isn't meaningful: about half the players sit below average, so the denominator crosses zero. Turn standardize off, or rewrite with + and −." The span covers the `÷` and its right side (F2) |

**Valid, and so not errors** (N4): `usg + -tov`, `usg * -2` and `-(usg)` (unary minus).
~~`pts * ts / 100` with standardize on is also valid, because it divides by a constant.~~
*(Amended after code review, B1: that was wrong. It multiplies two stats, and with standardize on the product of two z-scores ranks a player who is below average on both first. It is now an error with standardize on, and valid with it off. A stat times a constant, such as `usg * -2` or `(usg + ts) * 2`, stays valid.)*

**Generated test** (F1): for every input in `PICKABLE`, id → readable → parse → id must
round-trip, and tokens must be unique case-insensitively. A future label containing `/` or a
space then fails the suite rather than the user.

## Canonical form

After parsing, the formula is re-serialized over ids, with single spaces around binary
operators and minimal brackets. For example, typing `(USG+VOR)/TO` yields `(usg + vor) / tov`.
The URL and saved entries store this form. The readable form is **printed from the parse tree**, using
tokens and `×`/`÷`: `(USG + VOR) ÷ TO`. It is never produced by string substitution (F1). VOR's
"(fantasy)" tag is rendered beside the token as a styled suffix, outside the formula text.

## Evaluation

The usual precedence applies, and left-associative. Any `null` input or `÷ 0` short-circuits to
an exclusion reason, as defined in data-model "Result row". Evaluation never returns `NaN` or
`±Infinity` to the UI. *(Amended after code review, B3: this was breached by a bare constant or an oversized literal; the lexer now rejects a non-finite number and `validate` rejects a formula with no stat.)*
