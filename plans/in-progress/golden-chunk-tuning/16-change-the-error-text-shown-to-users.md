# Q16 v1 — How do I change the wording of the error message a user sees when they leave a required field blank or type something too long?

**Grounded source**: `44f4ead4bc6a` (Validation, altinn-studio). **Register**: lay — form-builder talking about "error message wording" rather than schema keywords/textResourceBindings.

## Goldens (read-confirmed)
- `9d8f106ba368` — core: lists the default error messages per rule (required, maxLength, etc.) and explains how to override the required-field message via `shortName` / `requiredValidation` text keys.
- `9db4a2d762fa` — core: how to define a custom `errorMessage` in the JSON schema for a field, with multilingual text-key support.

## Cited chunks

`9d8f106ba368` `9db4a2d762fa`
