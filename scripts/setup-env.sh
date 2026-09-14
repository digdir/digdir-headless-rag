#!/bin/sh
# First-run setup, host half: generate the secrets that can be generated and
# prompt only for the ones that cannot. Writes .env (#489 deliverable 2).
#
# WHY THIS IS A SHELL SCRIPT AND HAS NO DEPENDENCIES
#
#   .env is read by `docker compose` ON THE HOST, so no container-side tool can
#   write it. And the person who needs this is precisely the newcomer with no
#   toolchain — if it needed bb, or clojure, or a JVM, it would have failed at
#   its only job. POSIX sh, plus tools present on any macOS or Linux box:
#   head, base64, tr, grep, mv. No bb. No clojure. No node. No jq.
#
# WHAT IT DOES NOT DO
#
#   It does not touch the database. Seeding config belongs on the jar the image
#   already ships, because the database lives in the container:
#
#       docker compose -f docker-compose.newcomer.yml run --rm digdir-rag \
#         java -cp /app/app.jar clojure.main -m digdir.setup.bootstrap
#
# ⚠️ MARKER AND VARIABLE NAMES ARE DUPLICATED FROM CLOJURE, ON PURPOSE, AND
#    GUARDED. A shell script cannot read a Clojure def, so `PLACEHOLDER_MARKER`
#    and the generated-variable list below are the one place this repository
#    holds a second copy. `setup-env-script-test` asserts both against
#    `digdir.boot.placeholder-secrets`, so they cannot drift silently — which is
#    the same remedy used where `.env.example`'s convention had to agree with
#    the boot check.

set -eu

ENV_FILE="${ENV_FILE:-.env}"
EXAMPLE_FILE="${EXAMPLE_FILE:-.env.example}"

# Must equal digdir.boot.placeholder-secrets/placeholder-marker — asserted by a test.
PLACEHOLDER_MARKER="changeme"

# Generated, never asked. Must all be in the derived secret set — asserted by a test.
GENERATED_VARS="CONFIG_MASTER_KEY JWT_SECRET TYPESENSE_API_KEY_ADMIN"

# Prompted, because they cannot be invented.
PROMPTED_VARS="AZURE_OPENAI_API_KEY AZURE_OPENAI_API_ENDPOINT AZURE_OPENAI_DEPLOYMENT_NAME"

# Prompted, but genuinely OPTIONAL: infrastructure this product ships no value
# for. Skipping them is a supported outcome, not a deferred task — which is why
# this is a separate list rather than more entries in PROMPTED_VARS, whose skip
# message ("still needs a value") would be false here.
# Every name must be :tier :optional in digdir.config.env-bridge — asserted by
# setup-env-script-test, because this is a second copy of names Clojure owns.
OPTIONAL_PROMPTED_VARS="COLBERT_API_URL COLBERT_API_KEY"

# Prompted too, and a THIRD consequence — which is why it is a third list and
# not entries in either of the two above. This file already groups by what
# skipping COSTS rather than by how the value is obtained, and neither existing
# message is true here: PROMPTED_VARS says "still needs a value before the LLM
# path works" (wrong subsystem), and OPTIONAL_PROMPTED_VARS says skipping is a
# supported outcome (it is not — skipping this leaves a stack that starts,
# serves, and refuses every login).
#
# This is the fix for a real first-run failure (#515b): .env.example ships
# ADMIN_USER_EMAILS COMMENTED OUT, so a newcomer who edits the address but
# leaves the leading `#` gets no admin and no diagnostic. Asking here means the
# step does not depend on noticing a comment character. `needs_value` treats
# absent, commented-out and still-`changeme` identically, so all three states
# reach the prompt and all three end up written uncommented.
ACCESS_VARS="ADMIN_USER_EMAILS"

# A FOURTH list, on the same principle as the third: this file groups by what
# skipping COSTS, and this one's cost is CONDITIONAL, which neither existing
# message can say.
#
#   PROMPTED_VARS says "still needs a value before the LLM path works" — false
#   for someone deliberately running a local model, for whom leaving this unset
#   is the correct and intended state.
#
#   OPTIONAL_PROMPTED_VARS says skipping is a supported outcome — true here, but
#   that list is asserted by setup-env-script-test to hold only `:tier :optional`
#   bindings, and this one is `:tier :query`. Putting it there would break that
#   guard rather than satisfy it.
#
# So it gets its own prompt and a message that states the condition: blank is
# right if you are not using Azure, and wrong if you just supplied Azure
# credentials above.
#
# WHY ASK AT ALL. Unset is a real choice, but it is indistinguishable from never
# having been offered one. A user supplied a key, an endpoint and a deployment
# name, left this unset because nothing asked, and every query then failed with
# `Missing secret :openai-api-key` — a message about the OTHER provider. Asking
# means a clean install always has a CHOSEN provider rather than no value at all.
PROVIDER_VARS="AZURE_OPENAI_USE_AZURE"

say() { printf '%s\n' "$*"; }

# Can we actually OPEN a terminal? Probed once, by opening it — see the note at
# the prompt loop for why an existence test is not enough.
if { : >/dev/tty; } 2>/dev/null; then TTY_AVAILABLE=1; else TTY_AVAILABLE=0; fi

# 32 bytes of urandom, base64, punctuation stripped so the value is safe
# unquoted in a .env that docker compose parses. /dev/urandom rather than
# openssl: openssl is common but not guaranteed, and this must not need it.
generate_secret() {
  head -c 32 /dev/urandom | base64 | tr -d '\n=+/' | cut -c1-40
}

# The current value of a variable in the env file, or empty.
current_value() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -n 1 | cut -d= -f2- || true
}

# Absent, empty, or still holding the placeholder marker.
needs_value() {
  _v=$(current_value "$1")
  [ -z "$_v" ] && return 0
  case "$_v" in *"$PLACEHOLDER_MARKER"*) return 0 ;; esac
  return 1
}

# Set VAR=value, replacing the line if present and appending if not. Written to
# a temp file and moved, so an interrupted run cannot leave a half-written .env.
set_var() {
  _name="$1"; _value="$2"
  _tmp="${ENV_FILE}.tmp.$$"
  if grep -qE "^${_name}=" "$ENV_FILE" 2>/dev/null; then
    # `awk` avoids sed's platform-dependent -i and any escaping of the value.
    awk -v n="$_name" -v v="$_value" \
      'BEGIN{FS=OFS="="} $1==n {print n "=" v; next} {print}' \
      "$ENV_FILE" > "$_tmp"
  else
    cp "$ENV_FILE" "$_tmp"
    printf '%s=%s\n' "$_name" "$_value" >> "$_tmp"
  fi
  mv "$_tmp" "$ENV_FILE"
}

say ""
say "digdir — first-run setup (host half)"
say "===================================="
say ""

if [ ! -f "$ENV_FILE" ]; then
  if [ ! -f "$EXAMPLE_FILE" ]; then
    say "  ✖ Neither $ENV_FILE nor $EXAMPLE_FILE found."
    say "    Run this from the repository root."
    exit 1
  fi
  cp "$EXAMPLE_FILE" "$ENV_FILE"
  say "  · created $ENV_FILE from $EXAMPLE_FILE"
fi

generated=0
kept=0

say "  Generating secrets that can be generated:"
for v in $GENERATED_VARS; do
  if needs_value "$v"; then
    set_var "$v" "$(generate_secret)"
    say "      $v  generated"
    generated=$((generated + 1))
  else
    say "      $v  already set, left alone"
    kept=$((kept + 1))
  fi
done

say ""
say "  Values that cannot be invented:"
for v in $PROMPTED_VARS; do
  if needs_value "$v"; then
    # Prompt via /dev/tty so this still asks when stdout is piped. ⚠️ TEST BY
    # OPENING IT, not with `[ -e /dev/tty ]`: the device node EXISTS in a
    # non-interactive context where opening it fails, so the existence test
    # passes and the redirect then writes "Device not configured" to stderr on
    # every prompt. Observed, and the reason this is a `{ : >/dev/tty; }` probe.
    if [ "$TTY_AVAILABLE" = "1" ]; then
      printf '      %s (blank to skip): ' "$v" > /dev/tty
      read -r answer < /dev/tty || answer=""
    else
      answer=""
    fi
    if [ -n "${answer:-}" ]; then
      set_var "$v" "$answer"
      say "      $v  set"
    else
      say "      $v  SKIPPED — still needs a value before the LLM path works"
    fi
  else
    say "      $v  already set, left alone"
  fi
done

say ""
say "  Optional services — blank is a fine answer:"
for v in $OPTIONAL_PROMPTED_VARS; do
  if needs_value "$v"; then
    if [ "$TTY_AVAILABLE" = "1" ]; then
      printf '      %s (blank to skip): ' "$v" > /dev/tty
      read -r answer < /dev/tty || answer=""
    else
      answer=""
    fi
    if [ -n "${answer:-}" ]; then
      set_var "$v" "$answer"
      say "      $v  set"
    else
      say "      $v  skipped — reranking stays off. Answers keep retrieval"
      say "                            order: slightly worse ORDERING, not fewer"
      say "                            or wrong answers."
    fi
  else
    say "      $v  already set, left alone"
  fi
done

say ""
say "  Which LLM provider should this instance use?"
say "    true  = Azure OpenAI, using the three values above."
say "    false = any OpenAI-compatible server, including a local one."
for v in $PROVIDER_VARS; do
  if needs_value "$v"; then
    if [ "$TTY_AVAILABLE" = "1" ]; then
      printf '      %s (true or false): ' "$v" > /dev/tty
      read -r answer < /dev/tty || answer=""
    else
      answer=""
    fi
    if [ -n "${answer:-}" ]; then
      set_var "$v" "$answer"
      say "      $v  set"
    else
      # Deliberately not defaulted. Guessing from the presence of an Azure key
      # would be inferring intent, and writing the wrong provider is worse than
      # leaving the choice visible — the boot check names this exact state.
      say "      $v  SKIPPED — correct if you are NOT using Azure. If you"
      say "                            supplied Azure values above, the server"
      say "                            will refuse to start until you set this."
    fi
  else
    say "      $v  already set, left alone"
  fi
done

say ""
say "  Who may administer this instance:"
for v in $ACCESS_VARS; do
  if needs_value "$v"; then
    if [ "$TTY_AVAILABLE" = "1" ]; then
      printf '      %s (comma/space-separated, blank to skip): ' "$v" > /dev/tty
      read -r answer < /dev/tty || answer=""
    else
      answer=""
    fi
    if [ -n "${answer:-}" ]; then
      set_var "$v" "$answer"
      say "      $v  set"
    else
      say "      $v  SKIPPED — no admin will exist and every login will be refused"
    fi
  else
    say "      $v  already set, left alone"
  fi
done

say ""
say "  $generated generated, $kept left alone."
say ""

# Names only, never values — the same rule the Clojure side follows, and it
# matters more here because this script has just handled every one of them.
remaining=""
for v in $GENERATED_VARS $PROMPTED_VARS; do
  if needs_value "$v"; then remaining="$remaining $v"; fi
done

if [ -n "$remaining" ]; then
  say "  ⚠ Still placeholder or empty:$remaining"
  say "    The server will refuse to start until these are real values."
  say ""
fi

# Reported separately because the consequence is different: the server starts
# fine without this one. It just cannot be used by anybody.
for v in $ACCESS_VARS; do
  if needs_value "$v"; then
    say "  ⚠ Still unset: $v"
    say "    The stack will start and refuse every login, with nothing saying why."
    say "    Set it in $ENV_FILE (uncommented) and re-run the database half."
    say ""
  fi
done

# ⚠️ THE SERVICE NAME AND THE -f ARE BOTH LOAD-BEARING, and both were wrong.
# This used to name a service that no compose file here defines; the newcomer
# stack defines digdir-rag. And there is no default compose file at the
# repository root, so without -f the command fails at "no configuration file
# provided" before the service name is even read. Both failures reach the
# newcomer as "the product is broken". Guarded by setup-env-script-test, which
# derives the service list from the compose file rather than restating it.
#
# NOTE the guard reads COMMENTS too, deliberately — the header block above
# carries a copyable example. So do not quote a wrong command in prose here.
# ⚠️ ORDER IS THE FIX. Every digdir.setup.* -main opens the Datahike file store
# in a SECOND JVM. Against a running server the write does not survive, and the
# command reports success either way — measured on two fresh container stacks:
# first-admin printed "created 1" and the account did not exist afterwards,
# immediately and 45s later. #538 now makes these REFUSE with exit 1 when a
# server answers /up, so `exec` — which needs the service running — is an
# instruction guaranteed to fail. `run --rm` executes in a one-off container,
# which is what lets the seeding happen with nothing up.
#
# An earlier draft of this block told the reader to restart afterwards. That
# was the WRONG model: the account is not merely unseen until the server
# reconnects, so a restart has nothing to recover. Correct ordering DELETES the
# step (#526). Guarded below and by setup-env-script-test, which reads the
# PRINTED commands with continuations joined — a per-line check is blind here,
# because the compose call and the java call are on separate say lines.
say "  Next: seed the database, BEFORE starting the server —"
say ""
say "      docker compose -f docker-compose.newcomer.yml run --rm digdir-rag \\"
say "        java -cp /app/app.jar clojure.main -m digdir.setup.bootstrap"
say ""
# Seeding does NOT create admin accounts, and the sequence used to stop above.
# So this script asked who the admins were and then printed instructions that
# never consumed the answer — the capability existing while nothing on the
# documented path calls it (#532).
say "  Then create the admin account named above. Seeding does NOT create it:"
say ""
say "      docker compose -f docker-compose.newcomer.yml run --rm digdir-rag \\"
say "        java -cp /app/app.jar clojure.main -m digdir.setup.first-admin"
say ""
say "  Only now start the server. There is NO restart step:"
say ""
say "      docker compose -f docker-compose.newcomer.yml up -d"
say ""
say "  Seeded with nothing running, the account persists and login works on the"
say "  FIRST attempt. Seeded against a running server it silently does not, and"
say "  restarting afterwards does not recover it. docs/onboarding.md carries the"
say "  measurements (#526, #537, #538)."
say ""
