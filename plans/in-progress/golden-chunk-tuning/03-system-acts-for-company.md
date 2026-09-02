# Q3 v1 — How can an accounting program submit data to Altinn for a company without a person logging in each time?

**Grounded source**: `16de21c760d6` (System User Guide, authorization/system-vendor).
**Topic**: Authorization / system users (product_authorization) · **Diataxis**: how-to
**Register**: developer, vocabulary-mismatch (avoids "system user", "Maskinporten",
"machine-to-machine").

## Goldens (read-confirmed)
- `050fbe324462` — ch0: a system user is a virtual user an organization creates;
  gives software (e.g. an accounting program) access to retrieve/submit data on
  behalf of the org, with no person involved. Core definition + answer.
- `6f4a1ef342c8` — ch4: "system user for own system" — internal accounting system
  sends reports (A-melding, VAT) for the org's own organization number. Core
  (matches "accounting program for a company").
- `e6f7545a1adc` — ch2: how the system user is created (user-controlled). Supporting.

## Cited chunks

`050fbe324462` `6f4a1ef342c8` `e6f7545a1adc`
