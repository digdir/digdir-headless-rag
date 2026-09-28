#!/bin/sh
# Kobler hele Kudos-korpuset til den lokale stacken som tenant `kudos`,
# datasett `kudos-full`. Se docs/runbooks/kudos-full-lokalt.md.
#
#   scripts/kudos-full/seed.sh
#
# Kjøres fra repo-rota, én gang, etter at stacken har startet første gang.
# Leser .env.benjamin (fra Benjamin, gitignored) og stopper backenden mens
# config-databasen skrives, fordi databasen ikke tåler to prosesser samtidig.
#
# .env.benjamin har backendens egne config-stier som navn
# (`services.typesense.api-host = …`), ikke miljøvariabler. Her oversettes de
# til variablene env-broen leser. De nakne `url` og `key` er ColBERT.
#
# Verdiene sendes til containeren som `-e NAVN` uten verdi, så de havner ikke
# på kommandolinja og ikke i `ps`.
#
# SEED_DB_VOLUME=<volum> skriver til en kopi i stedet, uten å stoppe noe — for
# å prøve skriptet uten å røre den ekte databasen.

set -eu

COMPOSE="docker compose -f docker-compose.newcomer.yml"
SECRETS="${SECRETS_FILE:-.env.benjamin}"

[ -f "$SECRETS" ] || { echo "✘ fant ikke $SECRETS — be Benjamin om fila og legg den i repo-rota" >&2; exit 1; }

# Verdien etter «navn =», uten omkringliggende mellomrom og anførselstegn.
value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$SECRETS" \
    | head -n 1 | sed 's/[[:space:]]*$//; s/^"\(.*\)"$/\1/'
}

export TYPESENSE_API_HOST="$(value 'services\.typesense\.api-host')"
export TYPESENSE_API_KEY_ADMIN="$(value 'services\.typesense\.api-key-admin')"
export TYPESENSE_API_TLS="$(value 'services\.typesense\.api-tls')"
export KUDOS_COLLECTION_PREFIX="$(value 'pipeline\.storage\.collection-prefix')"
export TYPESENSE_COLLECTION_PREFIX="$KUDOS_COLLECTION_PREFIX"
export KUDOS_DOCS_COLLECTION="$(value 'pipeline\.storage\.docs-collection')"
export KUDOS_CHUNKS_COLLECTION="$(value 'pipeline\.storage\.chunks-collection')"
export KUDOS_PHRASES_COLLECTION="$(value 'pipeline\.storage\.phrases-collection')"
export COLBERT_API_URL="$(value 'url')"
export COLBERT_API_KEY="$(value 'key')"
export KUDOS_TENANT="${KUDOS_TENANT:-kudos}"
export KUDOS_DATASET="${KUDOS_DATASET:-kudos-full}"

for v in TYPESENSE_API_HOST TYPESENSE_API_KEY_ADMIN TYPESENSE_API_TLS \
         KUDOS_DOCS_COLLECTION KUDOS_CHUNKS_COLLECTION KUDOS_PHRASES_COLLECTION \
         COLBERT_API_URL COLBERT_API_KEY; do
  eval "[ -n \"\${$v}\" ]" || { echo "✘ $SECRETS mangler verdien som blir $v" >&2; exit 1; }
done

DB_ARGS=""
if [ -n "${SEED_DB_VOLUME:-}" ]; then
  DB_ARGS="-v $SEED_DB_VOLUME:/var/lib/seed-db -e DATAHIKE_FILE_PATH=/var/lib/seed-db/db"
else
  $COMPOSE stop digdir-rag
fi

status=0
# shellcheck disable=SC2086 # DB_ARGS er med vilje delt i ord
$COMPOSE run --rm --no-deps \
  -v "$(pwd)/scripts/kudos-full:/seed:ro" $DB_ARGS \
  -e TYPESENSE_API_HOST -e TYPESENSE_API_KEY_ADMIN -e TYPESENSE_API_TLS \
  -e TYPESENSE_COLLECTION_PREFIX -e KUDOS_COLLECTION_PREFIX \
  -e KUDOS_DOCS_COLLECTION -e KUDOS_CHUNKS_COLLECTION -e KUDOS_PHRASES_COLLECTION \
  -e COLBERT_API_URL -e COLBERT_API_KEY -e KUDOS_TENANT -e KUDOS_DATASET \
  --entrypoint java digdir-rag -cp /app/app.jar clojure.main /seed/seed.clj || status=$?

[ -n "${SEED_DB_VOLUME:-}" ] || $COMPOSE up -d digdir-rag
exit "$status"
