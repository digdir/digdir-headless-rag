# Hele Kudos-korpuset lokalt

Kobler den lokale stacken til Benjamins ferdig indekserte Kudos-korpus i
Typesense, `KUDOS_preprod_v4_*` på `typesense-test.digdir.cloud`:

| samling    | antall    |
| ---------- | --------- |
| dokumenter | 10 064    |
| biter      | 621 244   |
| fraser     | 6 564 478 |

Ingenting lastes inn. Samlingene finnes alt, så dette tar under ett minutt når
stacken står. Korpuset blir en egen tenant, `kudos`, med datasettet
`kudos-full`. Tenant `demo` og de lokale datasettene røres ikke.

## Fra ingenting

1. **Stacken.** Følg [`onboarding.md`](../onboarding.md) for
   `docker-compose.newcomer.yml`: `./scripts/setup-env.sh`, bootstrap før
   første start, `up -d`. Bygget tar om lag ti minutter. `/up` svarer «ok»
   etter om lag 40 sekunder.

   På en Mac uten Docker Desktop fungerer Colima:
   `colima start --cpu 6 --memory 12 --disk 60`. Colima deler bare
   hjemmemappa, så repoet må ligge under `~`.

2. **Hemmelighetene.** Be Benjamin om `.env.benjamin` og legg den i repo-rota.
   Den er gitignored (`.env.*`). Navnene i den er backendens egne config-stier
   (`services.typesense.api-host = …`), ikke miljøvariabler. Ikke gi dem nye
   navn, for skriptet leser dem slik de er. De to nakne linjene `url` og `key`
   er ColBERT-reranker-en.

3. **Seed.** Fra repo-rota:

   ```sh
   scripts/kudos-full/seed.sh
   ```

   Skriptet stopper backenden, skriver tenanten, starter backenden igjen, og
   avslutter med et frasesøk mot Kudos. Siste linje skal vise treff:

   ```
   frasesøk «DFØ årsrapport 2024»: 20 treff
   ```

   Null treff gir exit 1. Da mangler som regel samlingsnavnene på datasettets
   base-node, se under.

4. **Spør.** Med tenant `kudos` og `dataset_config_key` `kudos-full` i
   `tools/call`-argumentene. Et spørsmål tar fra 16 til 80 sekunder.

Frontenden som bruker dette er
[`kunnskapsassistenten-frontend`](https://github.com/larsekhansen/kunnskapsassistenten-frontend),
se `docs/kjoremiljo-og-korpus.md` der.

## Grenen

Hvis filteret og agentens `inspect_filters` skal virke, må du bruke
`fix/mcp-retrieve-filter-by` til den er merget. Med `main` svarer spørsmål,
men valgte filtre slippes uten feilmelding. Grenen retter seks feil:

1. `retrieve-filter-by` manglet på hvitelista over innstillinger per kall.
2. MCP-transporten gjorde ikke nøklene i `overrides` om til keywords, så ingen
   innstilling per kall har virket via MCP. Målt: `retrieve-top-k 7` ga 100.
3. Agentens søk lot modellens filter, som oftest tomt, erstatte leserens.
4. `value-type` kom som tekst og ble sammenlignet med et keyword, så et
   årsfilter ble sitert, og Typesense avviste det. Resultatet var 0 treff uten
   feilmelding.
5. En filterverdi kunne bli filtersyntaks. Målt: et årsfilter på 1 883
   dokumenter ble til 4 706.
6. `fetch-facets` krasjet alltid, fordi en lokal binding skygget
   `multi-search`, og `inspect_filters` leste `:name` i stedet for `:value`.
   Agenten fikk aldri se hvor mange dokumenter korpuset har, og svarte «ett
   dokument».

## Fallgruve: samlingsnavnene

`bootstrap-tenant-dataset-tree!` legger samlingsnavnene på
materialiseringsnoden, men kjøretiden leser dem fra datasettets base-node.
Uten `track-pipeline-collections!` søker den med samling `nil`, og du får
0 treff og «One or more search parameters are malformed». `seed.clj` gjør
begge.

## Prøve skriptet uten å røre databasen

```sh
docker volume create kudos-proeve
docker run --rm -v digdir-rag-newcomer_digdir-db:/fra -v kudos-proeve:/til \
  alpine sh -c 'cp -a /fra/. /til/'
SEED_DB_VOLUME=kudos-proeve KUDOS_TENANT=kudosproeve scripts/kudos-full/seed.sh
docker volume rm kudos-proeve
```

Kopier volumet mens backenden står stille. Datahike-lageret er filer som
skrives fortløpende.
