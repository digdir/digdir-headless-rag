# Security Policy

Digdir RAG-as-a-Service is an experimental **prototype**, but we still take
security seriously and welcome responsible disclosure of vulnerabilities.

## Reporting a vulnerability

**Do not** report security vulnerabilities through public GitHub issues, pull
requests, or discussions — this keeps details out of public view until a fix is
available.

Instead, report them privately through Digdir's contact channels, which are kept
up to date here: <https://www.digdir.no/digdir/kontakt-oss/943>.

Please include, where possible:

- a description of the vulnerability and its potential impact,
- steps to reproduce (a proof-of-concept if you have one),
- the affected version or commit, and
- any suggested remediation.

We will acknowledge your report as soon as we can and keep you informed of the
remediation progress. Please give us a reasonable opportunity to address the
issue before any public disclosure.

## Scope

This repository is an experimental prototype, not a production service. A real
deployment depends on external services (Postgres, Typesense, an LLM provider);
findings that require a running instance should note the configuration under
which they reproduce. Secrets must never be committed to this repository — see
[`CONTRIBUTING.md`](CONTRIBUTING.md).
