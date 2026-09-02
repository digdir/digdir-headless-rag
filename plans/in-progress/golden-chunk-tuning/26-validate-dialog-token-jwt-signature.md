# Q26 v1 — How does my resource server validate the signature on a dialog token JWT, and how do I handle key rotation?

**Grounded source**: `075f9f71a4c5` (Dialog tokens, dialogporten). **Register**: developer — explicitly about JWT signature validation and key set rotation on a resource server.

## Goldens (read-confirmed)
- `c2fd61822fa9` — core; documents EdDSA/Ed25519 signing, the well-known OAuth metadata endpoints for key discovery, JWK key-set rotation rules (min two keys, 48h publish window, 24h cache refresh) and validation recommendations.
- `18d80fdc4826` — core; states the 10-minute token lifetime and that a fresh token is issued per fetch, so clients must refetch.
- `ff208c3dcf0c` — supporting; describes the token as a self-contained signed JWT carried as an opaque bearer token in the Authorization header.

## Cited chunks

`c2fd61822fa9` `18d80fdc4826` `ff208c3dcf0c`
