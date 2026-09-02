# public-docs Retrieve-Debug Worksheet (Phase A output)

Generated 2026-04-20 via `/tmp/phase_a_worksheet.clj`. Single-query retrieval (query-relaxation bypassed). Auto-filter on.

For each candidate: top-20 merged chunks with [rank, chunk_id, url, 200-char snippet]. Scan and overwrite the annotation block with your marks.

## Annotation format

For each case replace the `**Annotation:**` line with one of: `keep`, `drop`, or `edit-query "<new query>"`.
Fill `**Goldens:**` with comma-separated chunk_ids (at least one) for `keep` cases. Leave blank for `drop`.
Add a freeform `**Notes:**` entry if useful.

---

## altinn-authorization-tilgangslister

**Query:** Hva er tilgangslister i Altinn Authorization og hva brukes de til?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/authorization/about/index.md or /nb/authorization/what-do-you-get/index.md

**Retrieval:** phrase=58 metadata=17 content=17 merged=73  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 2 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 3 | `7de055b54b63` | `/nb/authorization/index.md` | --- title: Autorisasjon description: Altinn Autorisasjon er en samling løsninger som gir tilgangsstyring og tilgangskontroll for digitale og analoge tjenester som kjører i Altinn-plattformen eller and… |
| 4 | `65ac88e194a6` | `/nb/authorization/about/index.md` | --- title: Om Altinn Autorisasjon description: Altinn Autorisasjon styrer hvem som skal kunne gjøre hva med hvilke data i det offentlige og i samspillet mellom offentlig og privat. aliases: [/technolo… |
| 5 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 6 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 7 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 8 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 9 | `a9ecb74f9296` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Krav</summary> <p>Vi ønsker at brukeren før innsending presenteres for hva dataen skal benyttes til og samtykker (indirekte) til dette ved å sende inn skjemaet.</p> <h3 id="mulige-… |
| 10 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 11 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 12 | `cdd0fa0520d6` | `/nb/broker/broker-transition/getting-started/index.md` | For å konfigurere ressursen slik at den fungerer optimalt i overgangsløsningen, og at den oppfører seg mest mulig som en Altinn 2 tjeneste, så bør du [sette følgende konfigurasjonverdier på ressursen]… |
| 13 | `875df4fd624d` | `/nb/authorization/what-do-you-get/rules/index.md` | Tilgang til tjenester i Altinn styres gjennom fullmakter. En virksomhet kan gi fullmakt til en tjeneste via to nivåer: Direkte til en enkelttjeneste, eller til tilgangspakken eller rollen tjenesten ti… |
| 14 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 15 | `fa2efd7bb647` | `/nb/broker/broker-transition/getting-started/index.md` | Etter at overgangsoppsettet er aktivert, vil ikke Altinn 2 tjenesten lenger sende forespørsler til Altinn 2 Formidling sitt datalager, da disse forespørselene isteden går til Altinn 3. Dette betyr at … |
| 16 | `5b7220458fe7` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Hvis du har registrert deg i Altinn Studio tidligere, via Github eller epostadresse, kan du koble din konto i Altinn Studio mot innlogging i Ansattporten ved å følge stegene under. 1. Gå til [altinn.… |
| 17 | `fe3c049029fc` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | Noen klientforhold opprettes automatisk basert på roller registrert i Enhetsregisteret. Disse forholdene gir deg tilgang til å bruke visse tilgangspakker når du oppretter en systembruker for klientfor… |
| 18 | `cff916b3803d` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Ved import opprettes tilgangsregler som speiler reglene i Altinn 2. Legg til aktuelle tilgangspakker slik at tjenesten er klar for overgang til tilgangspakker basert på Altinn-roller. ![Migration](m… |
| 19 | `86ad5a4683f5` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Dra og slipp en type fra panelet på venstresiden inn i trevisningen (midtseksjonen). Dette vil opprette et nytt felt med den valgte typen. Du kan også legge en typereferanse til et underordnet felt v… |
| 20 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-authorization-regler

**Query:** Hva er tilgangsregler i Altinn Authorization?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/authorization/what-do-you-get/rules/index.md

**Retrieval:** phrase=64 metadata=4 content=17 merged=73  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `7de055b54b63` | `/nb/authorization/index.md` | --- title: Autorisasjon description: Altinn Autorisasjon er en samling løsninger som gir tilgangsstyring og tilgangskontroll for digitale og analoge tjenester som kjører i Altinn-plattformen eller and… |
| 2 | `65ac88e194a6` | `/nb/authorization/about/index.md` | --- title: Om Altinn Autorisasjon description: Altinn Autorisasjon styrer hvem som skal kunne gjøre hva med hvilke data i det offentlige og i samspillet mellom offentlig og privat. aliases: [/technolo… |
| 3 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 4 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 5 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 6 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 7 | `6ea4d4ac1d99` | `/nb/correspondence/index.md` | --- title: Altinn 3 Melding description: Altinn 3 Melding ('Correspondence' på engelsk) er en meldingstjeneste for sikker utveksling av korrespondanse, som offisielle brev, varsler og andre dokumenter… |
| 8 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 9 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 10 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 11 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 12 | `2860727058b0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Enkeltpersonforetak er spesielle på den måten at det er innehaver selv som er 100% ansvarlig for virksomheten. Derfor får nøstes enkelte fullmakter videre til innehavers personnummer for regnskapsføre… |
| 13 | `f731ee7ddee5` | `/nb/authorization/reference/architecture/accesscontrol/pip/index.md` | --- title: Policy Informasjonspunkt description: Policy Informasjonspunkt(er) er ansvarlige for å gi nødvendig informasjon til konteksthåndtereren slik at den kan berike kontekstforespørselen. diataxi… |
| 14 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 15 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 16 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 17 | `66aa09439e8b` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Alle kan se på datamodellene til en organisasjon som standard. For å få tilgang til å redigere datamodellene må brukeren være del av _Datamodels_-teamet i organisasjonen. Se [tilgangsstyring][3] for m… |
| 18 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 19 | `a9ecb74f9296` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Krav</summary> <p>Vi ønsker at brukeren før innsending presenteres for hva dataen skal benyttes til og samtykker (indirekte) til dette ved å sende inn skjemaet.</p> <h3 id="mulige-… |
| 20 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-authorization-rules-getting-started

**Query:** Hvordan kommer jeg i gang med å lage tilgangsregler?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/authorization/getting-started/rules/index.md

**Retrieval:** phrase=65 metadata=1 content=18 merged=74  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 2 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 3 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 4 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 5 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 6 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 7 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 8 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 9 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 10 | `cff916b3803d` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Ved import opprettes tilgangsregler som speiler reglene i Altinn 2. Legg til aktuelle tilgangspakker slik at tjenesten er klar for overgang til tilgangspakker basert på Altinn-roller. ![Migration](m… |
| 11 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 12 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 13 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 14 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 15 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 16 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 17 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 18 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 19 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 20 | `d86b0c860b67` | `/nb/correspondence/about/index.md` | Man kan bruke Altinn som tjenesteeier for å sende meldinger eller integrere seg mot Altinn som sluttbrukersystem for å vise meldinger: <details> <summary>Tjenesteeier</summary> <p>Er en offentlig vi… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-systemuser-api-opprett

**Query:** Hvordan oppretter jeg en systembruker via Altinn system user API?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/api/authentication/systemuserapi/systemregister/create/index.md

**Retrieval:** phrase=69 metadata=1 content=18 merged=80  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 2 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 3 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 4 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 5 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 6 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 7 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 8 | `3018b3b4b1cd` | `/nb/events/reference/architecture/events/index.md` | API-kontrollerne listet nedenfor er utelukkende for bruk innenfor Notification-løsningen: - [StorageController](https://github.com/Altinn/altinn-events/blob/main/src/Events/Controllers/StorageControl… |
| 9 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 10 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 11 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 12 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 13 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 14 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 15 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 16 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 17 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 18 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 19 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 20 | `506493584a14` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | Du må ha noen forutsetninger på plass før du registrer et system, se [Kom i gang-veiledningen](https://docs.altinn.studio/nb/authorization/getting-started/systemuser/). ----- Registrering av et slut… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-systemuser-api-model

**Query:** Hvordan ser datamodellen for en system user ut i Altinn?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/api/authentication/systemuserapi/systemregister/model/index.md

**Retrieval:** phrase=61 metadata=13 content=18 merged=78  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 2 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 3 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 4 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 5 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 6 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 7 | `64cc1aee310d` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Altinn Studio Datamodellering er et verktøy for å utvikle [datamodeller](#datamodeller). Den baserer seg på en datamodell i JSON Schema format og kan ut fra dette generere XSD- og C#-modeller (se [Dat… |
| 8 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 9 | `d86b0c860b67` | `/nb/correspondence/about/index.md` | Man kan bruke Altinn som tjenesteeier for å sende meldinger eller integrere seg mot Altinn som sluttbrukersystem for å vise meldinger: <details> <summary>Tjenesteeier</summary> <p>Er en offentlig vi… |
| 10 | `5eb892198d87` | `/nb/authorization/what-do-you-get/authentication/index.md` | Når en bruker logger inn i Altinn-portalen (gammel løsning), utstedes en informasjonskapsel (cookie) som inneholder informasjon om den autentiserte brukeren. Denne informasjonskapselen bruker [et prop… |
| 11 | `2f6cee8d5f5e` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | 1. Logg inn i Altinn Studio. Hvis du ikke befinner deg i [Altinn Studio Dashboard](/nb/altinn-studio/v8/getting-started/navigation/dashboard/), naviger dit ved å klikke på logoen øverst i venstre hjør… |
| 12 | `d81ff032aa35` | `/nb/community/about/index.md` | Det er mange grunner til å bruke Altinn 3 til å bygge og kjøre dine digitale tjenester. - Applikasjonsmal som har innebygd funksjonalitet for de vanligste bruksområdene for digitale tjenester - Lett … |
| 13 | `05e9fe2c2c5b` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Eksisterende lenketjenester i Altinn 2 som brukes til ekstern autorisasjon, må flyttes til Ressursregisteret på Altinn 3. I Altinn Studio kan du opprette nye ressurser basert på disse lenketjenestene… |
| 14 | `a1e19ce9cac2` | `/nb/events/reference/architecture/events/index.md` | | Tjeneste | Formål | Ressurser | | -------------------- | --------------------… |
| 15 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 16 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 17 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 18 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 19 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 20 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-systemuser-accept-request

**Query:** Hvordan aksepterer en sluttbruker en forespørsel om system user i Altinn?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/authorization/guides/end-user/system-user/accept-request/index.md

**Retrieval:** phrase=67 metadata=13 content=18 merged=83  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 2 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 3 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 4 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 5 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 6 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 7 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 8 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 9 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 10 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 11 | `f2c87d24c5be` | `/nb/broker/about/index.md` | Altinn Formidling gir styrt filoverføring (Managed File Transfer - MFT), med sikker overføring av store filer fra én avsender til en eller flere mottakere. Styrt filoverføring gir flere fordeler sam… |
| 12 | `d86b0c860b67` | `/nb/correspondence/about/index.md` | Man kan bruke Altinn som tjenesteeier for å sende meldinger eller integrere seg mot Altinn som sluttbrukersystem for å vise meldinger: <details> <summary>Tjenesteeier</summary> <p>Er en offentlig vi… |
| 13 | `d81ff032aa35` | `/nb/community/about/index.md` | Det er mange grunner til å bruke Altinn 3 til å bygge og kjøre dine digitale tjenester. - Applikasjonsmal som har innebygd funksjonalitet for de vanligste bruksområdene for digitale tjenester - Lett … |
| 14 | `05e9fe2c2c5b` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Eksisterende lenketjenester i Altinn 2 som brukes til ekstern autorisasjon, må flyttes til Ressursregisteret på Altinn 3. I Altinn Studio kan du opprette nye ressurser basert på disse lenketjenestene… |
| 15 | `a1e19ce9cac2` | `/nb/events/reference/architecture/events/index.md` | | Tjeneste | Formål | Ressurser | | -------------------- | --------------------… |
| 16 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 17 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 18 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 19 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 20 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-systemuser-delegate-clients

**Query:** Hvordan delegere klienter til en system user i Altinn?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/authorization/guides/end-user/system-user/delegate-clients/index.md

**Retrieval:** phrase=65 metadata=13 content=18 merged=83  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 2 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 3 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 4 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 5 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 6 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 7 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 8 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 9 | `d86b0c860b67` | `/nb/correspondence/about/index.md` | Man kan bruke Altinn som tjenesteeier for å sende meldinger eller integrere seg mot Altinn som sluttbrukersystem for å vise meldinger: <details> <summary>Tjenesteeier</summary> <p>Er en offentlig vi… |
| 10 | `05e9fe2c2c5b` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Eksisterende lenketjenester i Altinn 2 som brukes til ekstern autorisasjon, må flyttes til Ressursregisteret på Altinn 3. I Altinn Studio kan du opprette nye ressurser basert på disse lenketjenestene… |
| 11 | `d81ff032aa35` | `/nb/community/about/index.md` | Det er mange grunner til å bruke Altinn 3 til å bygge og kjøre dine digitale tjenester. - Applikasjonsmal som har innebygd funksjonalitet for de vanligste bruksområdene for digitale tjenester - Lett … |
| 12 | `5eb892198d87` | `/nb/authorization/what-do-you-get/authentication/index.md` | Når en bruker logger inn i Altinn-portalen (gammel løsning), utstedes en informasjonskapsel (cookie) som inneholder informasjon om den autentiserte brukeren. Denne informasjonskapselen bruker [et prop… |
| 13 | `a1a45b9d7585` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | For de fleste lenketjenester finnes det [aktive delegeringer i Altinn 2](https://github.com/Altinn/altinn-access-management/issues/579). Dette er rettigheter gitt fra én aktør til en person eller virk… |
| 14 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 15 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 16 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 17 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 18 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 19 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 20 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-studio-datamodeling

**Query:** Hvordan definerer jeg en datamodell i Altinn Studio v8?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/altinn-studio/v8/reference/data/data-modeling/index.md

**Retrieval:** phrase=61 metadata=0 content=18 merged=74  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 2 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 3 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 4 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 5 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 6 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 7 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 8 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 9 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 10 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 11 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 12 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 13 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 14 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 15 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 16 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 17 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 18 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 19 | `ab3581cfc118` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | --- title: Altinn Studio Datamodellering description: Datamodell og Datamodellering i Altinn Studio aliases: [/nb/altinn-studio/v8/reference/data/data-model/ /nb/altinn-studio/v8/reference/data/data-m… |
| 20 | `64cc1aee310d` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Altinn Studio Datamodellering er et verktøy for å utvikle [datamodeller](#datamodeller). Den baserer seg på en datamodell i JSON Schema format og kan ut fra dette generere XSD- og C#-modeller (se [Dat… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-studio-create-user

**Query:** Hvordan oppretter jeg en bruker i Altinn Studio v8?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/altinn-studio/v8/getting-started/create-user/index.md

**Retrieval:** phrase=73 metadata=0 content=18 merged=87  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 2 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 3 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 4 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 5 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 6 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 7 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 8 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 9 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 10 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 11 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 12 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 13 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 14 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 15 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 16 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 17 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 18 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 19 | `b088ac9af2de` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Datamodeller for organisasjoner ligger i Altinn Studio sin [repos][1]-løsning. For å få tilgang til disse må man logge inn med Altinn Studio bruker. Om det er første gang du bruker Altinn Studio må du… |
| 20 | `2d0fde59ac05` | `/nb/altinn-studio/v8/index.md` | --- title: Altinn Studio description: Altinn Studio - Ditt verktøy for utvikling av digitale tjenester til innbyggere og næringsliv aliases: [/altinn-studio/ /nb/altinn-studio/ /teknologi/altinnstudio… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-studio-grouping-repeating

**Query:** Hva er forskjellen mellom repeterende og ikke-repeterende grupper i Altinn Studio?

**Family:** `:paraphrase`  |  **Target doc (inventory):** /nb/altinn-studio/v8/reference/ux/fields/grouping/repeating/index.md + non-repeating

**Retrieval:** phrase=59 metadata=6 content=17 merged=69  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 2 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 3 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 4 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 5 | `65ac88e194a6` | `/nb/authorization/about/index.md` | --- title: Om Altinn Autorisasjon description: Altinn Autorisasjon styrer hvem som skal kunne gjøre hva med hvilke data i det offentlige og i samspillet mellom offentlig og privat. aliases: [/technolo… |
| 6 | `6ea4d4ac1d99` | `/nb/correspondence/index.md` | --- title: Altinn 3 Melding description: Altinn 3 Melding ('Correspondence' på engelsk) er en meldingstjeneste for sikker utveksling av korrespondanse, som offisielle brev, varsler og andre dokumenter… |
| 7 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 8 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 9 | `7de055b54b63` | `/nb/authorization/index.md` | --- title: Autorisasjon description: Altinn Autorisasjon er en samling løsninger som gir tilgangsstyring og tilgangskontroll for digitale og analoge tjenester som kjører i Altinn-plattformen eller and… |
| 10 | `a9ecb74f9296` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Krav</summary> <p>Vi ønsker at brukeren før innsending presenteres for hva dataen skal benyttes til og samtykker (indirekte) til dette ved å sende inn skjemaet.</p> <h3 id="mulige-… |
| 11 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 12 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 13 | `5d9c89c1afec` | `/nb/broker/broker-transition/technical-overview/index.md` | Siden manifestfiler er blitt avviklet i Altinn 3 Formidlingstjeneste, har vi lagt til en funksjon for å muliggjøre oppretting/oppdatering av manifestfiler for overgangsløsningen. Funksjonen er impleme… |
| 14 | `66aa09439e8b` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Alle kan se på datamodellene til en organisasjon som standard. For å få tilgang til å redigere datamodellene må brukeren være del av _Datamodels_-teamet i organisasjonen. Se [tilgangsstyring][3] for m… |
| 15 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 16 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 17 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 18 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 19 | `f731ee7ddee5` | `/nb/authorization/reference/architecture/accesscontrol/pip/index.md` | --- title: Policy Informasjonspunkt description: Policy Informasjonspunkt(er) er ansvarlige for å gi nødvendig informasjon til konteksthåndtereren slik at den kan berike kontekstforespørselen. diataxi… |
| 20 | `fd3c5cddbfbb` | `/nb/altinn-studio/v8/reference/ux/fields/grouping/non-repeating/index.md` | --- title: Gruppering av enkeltfelter description: Oppsett for ikke-repeterende grupper diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST linktitle… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-studio-grouping-repeating-edit

**Query:** Hvordan redigerer jeg en repeterende gruppe i Altinn Studio v8?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/altinn-studio/v8/reference/ux/fields/grouping/repeating/edit/index.md

**Retrieval:** phrase=65 metadata=0 content=18 merged=78  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 2 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 3 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 4 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 5 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 6 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 7 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 8 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 9 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 10 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 11 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 12 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 13 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 14 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 15 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 16 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 17 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 18 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 19 | `fd3c5cddbfbb` | `/nb/altinn-studio/v8/reference/ux/fields/grouping/non-repeating/index.md` | --- title: Gruppering av enkeltfelter description: Oppsett for ikke-repeterende grupper diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST linktitle… |
| 20 | `9d621302f88b` | `/nb/altinn-studio/v8/reference/ux/fields/grouping/repeating/index.md` | --- title: Repeterende grupper description: Oppsett for repeterende grupper diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST linktitle: Repeterend… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-broker-getting-started

**Query:** Hvordan kommer jeg i gang med Altinn Broker under overgangen til Altinn 3?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/broker/broker-transition/getting-started/index.md

**Retrieval:** phrase=63 metadata=8 content=18 merged=77  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 2 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 3 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 4 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 5 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 6 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 7 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 8 | `a1a45b9d7585` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | For de fleste lenketjenester finnes det [aktive delegeringer i Altinn 2](https://github.com/Altinn/altinn-access-management/issues/579). Dette er rettigheter gitt fra én aktør til en person eller virk… |
| 9 | `d86b0c860b67` | `/nb/correspondence/about/index.md` | Man kan bruke Altinn som tjenesteeier for å sende meldinger eller integrere seg mot Altinn som sluttbrukersystem for å vise meldinger: <details> <summary>Tjenesteeier</summary> <p>Er en offentlig vi… |
| 10 | `d81ff032aa35` | `/nb/community/about/index.md` | Det er mange grunner til å bruke Altinn 3 til å bygge og kjøre dine digitale tjenester. - Applikasjonsmal som har innebygd funksjonalitet for de vanligste bruksområdene for digitale tjenester - Lett … |
| 11 | `05e9fe2c2c5b` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Eksisterende lenketjenester i Altinn 2 som brukes til ekstern autorisasjon, må flyttes til Ressursregisteret på Altinn 3. I Altinn Studio kan du opprette nye ressurser basert på disse lenketjenestene… |
| 12 | `cff916b3803d` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | Ved import opprettes tilgangsregler som speiler reglene i Altinn 2. Legg til aktuelle tilgangspakker slik at tjenesten er klar for overgang til tilgangspakker basert på Altinn-roller. ![Migration](m… |
| 13 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 14 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 15 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 16 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 17 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 18 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 19 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 20 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-broker-rest-usage

**Query:** Hvordan bruker jeg Altinn Broker med REST-API?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/broker/broker-transition/usage/rest/index.md

**Retrieval:** phrase=76 metadata=0 content=8 merged=79  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 2 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 3 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 4 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 5 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 6 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 7 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 8 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 9 | `05045ab7c1ab` | `/nb/broker/broker-transition/usage/rest/index.md` | REST-operasjoner for Formidling i Altinn 2 er logisk delt mellom avsender og mottaker (Outbox / inbox). Altinn 2 REST-operasjoner kombinerer initiering og opplasting til samme operasjon. Operasjonen … |
| 10 | `cdd0fa0520d6` | `/nb/broker/broker-transition/getting-started/index.md` | For å konfigurere ressursen slik at den fungerer optimalt i overgangsløsningen, og at den oppfører seg mest mulig som en Altinn 2 tjeneste, så bør du [sette følgende konfigurasjonverdier på ressursen]… |
| 11 | `06f45de29249` | `/nb/broker/broker-transition/usage/rest/index.md` | Overgangsløsningen bruker ikke FileList propertyen.Ingen endring er nødvendig fra sluttbrukerimplementeringen, men en nullverdi vil bli akseptert, og eventuelle sendte verdier vil bli ignorert av over… |
| 12 | `0f3807200a65` | `/nb/broker/broker-transition/usage/rest/index.md` | --- title: Bruksanvisning REST description: Forskjell i bruk av REST-operasjoner mellom Altinn 2 og Altinn 3 overførte tjenester. draft: false iscjklanguage: false lastmod: 2025-03-13 09:17:09 +0100 C… |
| 13 | `548c3bf390b6` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | Alle listevisninger i Dialogporten benytter [Authorized Parties API](/nb/authorization/guides/resource-owner/generic-access-resource/integrating-link-service/#integrasjon-med-api-for-autoriserte-parte… |
| 14 | `e4f6b3689263` | `/nb/broker/broker-transition/usage/rest/index.md` | For Altinn 3 Overgangs-tjenester er kvitteringen en pseudo-kvittering bygget av Altinn 3 Formidling fil metadata. Denne operasjonen verifiserer om noen av de gitte mottakerne har noen filer tilgjen… |
| 15 | `c48747e2b5eb` | `/nb/broker/broker-transition/technical-overview/index.md` | Altinn 2 lar sluttbrukere gjøre kall for spesifikke formidlingstjenester og overføre disse forespørslene til Altinn 3 basert på "*ServiceCode*"/"*ServiceEdition*" verdier i forespørselen. Filer overfø… |
| 16 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 17 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 18 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 19 | `abc7ff853176` | `/nb/broker/broker-transition/usage/rest/index.md` | En sekundær operasjon for å vise at filen er har "*Initialized*" status, som betyr at den ikke har blitt blitt behandlet, og at den kan ha blitt avvist. ```JSON { "ServiceCode": "4947", "Serv… |
| 20 | `a2ca157dce3d` | `/nb/broker/broker-transition/usage/rest/index.md` | ```JSON { "ReceiptID": 0, "ParentReceiptID": null, "LastChanged": "2024-05-08T12:14:52.313", "Status": "Ok", "Text": "Upload of file 0ed44efb-f397-43a7-883a-4be634c902f1 was succes… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-broker-technical-overview

**Query:** Hva er den tekniske arkitekturen bak Altinn Broker i overgangsfasen?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/broker/broker-transition/technical-overview/index.md

**Retrieval:** phrase=61 metadata=4 content=8 merged=68  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 2 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 3 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 4 | `5d9c89c1afec` | `/nb/broker/broker-transition/technical-overview/index.md` | Siden manifestfiler er blitt avviklet i Altinn 3 Formidlingstjeneste, har vi lagt til en funksjon for å muliggjøre oppretting/oppdatering av manifestfiler for overgangsløsningen. Funksjonen er impleme… |
| 5 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 6 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 7 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 8 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 9 | `f731ee7ddee5` | `/nb/authorization/reference/architecture/accesscontrol/pip/index.md` | --- title: Policy Informasjonspunkt description: Policy Informasjonspunkt(er) er ansvarlige for å gi nødvendig informasjon til konteksthåndtereren slik at den kan berike kontekstforespørselen. diataxi… |
| 10 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 11 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 12 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 13 | `2c9085ae6cc1` | `/nb/broker/broker-transition/getting-started/index.md` | For sluttbrukere er det veldig liten teknisk forskjell mellom å bruke en Altinn 2 Formidlingstjeneste og en formidlingstjeneste som er blitt overført til Altinn 3. Men alle forbrukere bør få det tekni… |
| 14 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 15 | `cdd0fa0520d6` | `/nb/broker/broker-transition/getting-started/index.md` | For å konfigurere ressursen slik at den fungerer optimalt i overgangsløsningen, og at den oppfører seg mest mulig som en Altinn 2 tjeneste, så bør du [sette følgende konfigurasjonverdier på ressursen]… |
| 16 | `c48747e2b5eb` | `/nb/broker/broker-transition/technical-overview/index.md` | Altinn 2 lar sluttbrukere gjøre kall for spesifikke formidlingstjenester og overføre disse forespørslene til Altinn 3 basert på "*ServiceCode*"/"*ServiceEdition*" verdier i forespørselen. Filer overfø… |
| 17 | `63c3d3e28820` | `/nb/broker/broker-transition/technical-overview/index.md` | "*Altinn 3 Broker Transition Service Bridge*" er en intern komponent i Altinn 2 som overfører Formidlings-forespørsler fra Altinn 2 til Altinn 3 for en gitt forespørsel, basert på "*ServiceCode*"/"*Se… |
| 18 | `54cf9b4a112e` | `/nb/broker/broker-transition/usage/soap/index.md` | Trinn 1: Initier filoverføring ```XML <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.altinn.no/services/ServiceEngine/Broker/2015/06" xmlns:ns1="http:… |
| 19 | `a1a45b9d7585` | `/nb/authorization/guides/resource-owner/migrate/linked_services/index.md` | For de fleste lenketjenester finnes det [aktive delegeringer i Altinn 2](https://github.com/Altinn/altinn-access-management/issues/579). Dette er rettigheter gitt fra én aktør til en person eller virk… |
| 20 | `d438d14968f7` | `/nb/broker/broker-transition/usage/soap/index.md` | "*BrokerService*" endepunktet inneholder funksjoner relatert til formidlingstjeneste metadata forespørsler. Ingen funksjonell forskjell for overførte tjenester. Ingen funksjonell forskjell for overf… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-correspondence-post-published

**Query:** Hva skjer med en correspondence etter at den er publisert i Altinn?

**Family:** `:factual`  |  **Target doc (inventory):** /nb/correspondence/explanation/status-lifecycle/post-published/index.md

**Retrieval:** phrase=69 metadata=6 content=18 merged=80  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 2 | `65ac88e194a6` | `/nb/authorization/about/index.md` | --- title: Om Altinn Autorisasjon description: Altinn Autorisasjon styrer hvem som skal kunne gjøre hva med hvilke data i det offentlige og i samspillet mellom offentlig og privat. aliases: [/technolo… |
| 3 | `a9ecb74f9296` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Krav</summary> <p>Vi ønsker at brukeren før innsending presenteres for hva dataen skal benyttes til og samtykker (indirekte) til dette ved å sende inn skjemaet.</p> <h3 id="mulige-… |
| 4 | `6ea4d4ac1d99` | `/nb/correspondence/index.md` | --- title: Altinn 3 Melding description: Altinn 3 Melding ('Correspondence' på engelsk) er en meldingstjeneste for sikker utveksling av korrespondanse, som offisielle brev, varsler og andre dokumenter… |
| 5 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 6 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 7 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 8 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 9 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 10 | `7de055b54b63` | `/nb/authorization/index.md` | --- title: Autorisasjon description: Altinn Autorisasjon er en samling løsninger som gir tilgangsstyring og tilgangskontroll for digitale og analoge tjenester som kjører i Altinn-plattformen eller and… |
| 11 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 12 | `2d0fde59ac05` | `/nb/altinn-studio/v8/index.md` | --- title: Altinn Studio description: Altinn Studio - Ditt verktøy for utvikling av digitale tjenester til innbyggere og næringsliv aliases: [/altinn-studio/ /nb/altinn-studio/ /teknologi/altinnstudio… |
| 13 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 14 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 15 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 16 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 17 | `f731ee7ddee5` | `/nb/authorization/reference/architecture/accesscontrol/pip/index.md` | --- title: Policy Informasjonspunkt description: Policy Informasjonspunkt(er) er ansvarlige for å gi nødvendig informasjon til konteksthåndtereren slik at den kan berike kontekstforespørselen. diataxi… |
| 18 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 19 | `66aa09439e8b` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Alle kan se på datamodellene til en organisasjon som standard. For å få tilgang til å redigere datamodellene må brukeren være del av _Datamodels_-teamet i organisasjonen. Se [tilgangsstyring][3] for m… |
| 20 | `0fac42eefdc4` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Din Altinn Studio bruker er personlig for deg og kan knyttes til en eller flere organisasjoner for å samarbeide med andre og få tilgang til eksisterende apper. {.floating-bullet-numbers-sibling-ol} … |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-events-architecture

**Query:** Hvordan er Altinn Events arkitektonisk bygget opp?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/events/reference/architecture/events/index.md

**Retrieval:** phrase=70 metadata=3 content=8 merged=76  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 2 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 3 | `54cf9b4a112e` | `/nb/broker/broker-transition/usage/soap/index.md` | Trinn 1: Initier filoverføring ```XML <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.altinn.no/services/ServiceEngine/Broker/2015/06" xmlns:ns1="http:… |
| 4 | `dc651e09a506` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Det er Digdir som oppretter organisasjoner i Altinn Studio. For å kunne få en organisasjon i Altinn Studio må din virksomhet - være tjenesteeier og ha inngått en avtale med Altinn, eller - tilby tje… |
| 5 | `e330142e8502` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Datamodellen definerer hvilke data som kan sendes inn via en app, og hvilket format det skal sendes på. Vi støtter pr. nå kun opplasting av XSD datamodell. Ved opplasting av XSD-modell `<model>.xsd` v… |
| 6 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 7 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 8 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 9 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 10 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 11 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 12 | `62e589466018` | `/nb/events/reference/architecture/events/index.md` | Azure Storage-køer har blitt satt opp for å gjøre det mulig for .NET-applikasjonen og Azure Functions å sende data for prosessering av en annen tjeneste. Logikk for innlegging av elementer i kø håndt… |
| 13 | `06f45de29249` | `/nb/broker/broker-transition/usage/rest/index.md` | Overgangsløsningen bruker ikke FileList propertyen.Ingen endring er nødvendig fra sluttbrukerimplementeringen, men en nullverdi vil bli akseptert, og eventuelle sendte verdier vil bli ignorert av over… |
| 14 | `7d33c4c5baf1` | `/nb/events/reference/architecture/events/index.md` | --- title: Events diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST linktitle: Events product: product_events tags: [architecture solution events] … |
| 15 | `7edffb93ea95` | `/nb/events/reference/architecture/events/index.md` | Kvalitetsporter implementert for et prosjekt krever 80 % kodedekning for enhets- og integrasjonstestene kombinert. [xUnit](https://xunit.net/) er rammeverket som brukes, og [Moq-biblioteket](https://g… |
| 16 | `b0bb14d97ad0` | `/nb/events/reference/architecture/events/index.md` | Regresjonstester er verter i [Azure DevOps pipelines](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=96) og utløses ved slutten av en release pipeline. Mikrotjenesten kjører i en Doc… |
| 17 | `a1e19ce9cac2` | `/nb/events/reference/architecture/events/index.md` | | Tjeneste | Formål | Ressurser | | -------------------- | --------------------… |
| 18 | `66aa09439e8b` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Alle kan se på datamodellene til en organisasjon som standard. For å få tilgang til å redigere datamodellene må brukeren være del av _Datamodels_-teamet i organisasjonen. Se [tilgangsstyring][3] for m… |
| 19 | `c1e41c9b98c2` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | ⚠️ **Warning**: For å godkjenne en systemtilgang-forespørsel må innlogget bruker ha rollen Tilgangsstyrer i valgt organisasjon (f.eks har Daglig leder rollen Tilgangsstyrer), samt ha de forespurte ful… |
| 20 | `d4be96620198` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | 1. Her godkjenner en sluttbruker en forespørsel om å opprette en Systemtilgang for klienter. For å godkjenne en systemtilgang-forespørsel må innlogget bruker ha rollen Tilgangsstyrer i valgt organisas… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-dialogporten-about

**Query:** Hva er Dialogporten?

**Family:** `:exact-lookup`  |  **Target doc (inventory):** /nb/dialogporten/index.md or /nb/dialogporten/reference/authorization/altinn-authorization/index.md

**Retrieval:** phrase=80 metadata=4 content=17 merged=89  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 2 | `aa36957253ca` | `/nb/broker/about/index.md` | --- title: Om Altinn Formidling description: Hva er Altinn Formidling? cascade: map[params:map[diataxis:diataxis_explanation]] diataxis: diataxis_explanation draft: false iscjklanguage: false lastmod:… |
| 3 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 4 | `2fdfadae4f0d` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Navngivning</summary> <ul> <li> <p>Tjenesten må ha et fornuftig navn som gjør det enkelt å finne den igjen blant det store antallet tjenester Sogndal kommune har i Altinn Studio.</… |
| 5 | `1f287112d201` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | --- title: Casebeskrivelse description: Beskrivelse av kommunens krav og ønsker til tjenesten. diataxis: diataxis_tutorials draft: false iscjklanguage: false lastmod: 2025-09-29 11:19:41 +0200 CEST li… |
| 6 | `bf7466ce03c2` | `/nb/authorization/what-do-you-get/rules/index.md` | Altinn bruker samme overordnede prinsipper for autorisasjon, uavhengig av om tilgangen gjelder en ressurs eller en app utviklet i Altinn Studio. Dette gjør at virksomheter kan forholde seg til én tilg… |
| 7 | `e21b490e680b` | `/nb/broker/broker-transition/about/index.md` | --- title: Om Altinn 3 Formidling overgangsløsningen description: Hva er Altinn 3 Formidling Overgangsløsning. draft: false iscjklanguage: false lastmod: 2024-11-14 08:50:26 +0100 CET linktitle: Om Ov… |
| 8 | `7de055b54b63` | `/nb/authorization/index.md` | --- title: Autorisasjon description: Altinn Autorisasjon er en samling løsninger som gir tilgangsstyring og tilgangskontroll for digitale og analoge tjenester som kjører i Altinn-plattformen eller and… |
| 9 | `a9ecb74f9296` | `/nb/altinn-studio/v8/getting-started/app-dev-course/case/index.md` | <details> <summary>Krav</summary> <p>Vi ønsker at brukeren før innsending presenteres for hva dataen skal benyttes til og samtykker (indirekte) til dette ved å sende inn skjemaet.</p> <h3 id="mulige-… |
| 10 | `71edc1fa4e22` | `/nb/broker/about/index.md` | Sammenlignet med alternativer som for eksempel e-post, FTP eller peer-to-peer filoverføringer, gir Altinn Formidling en rekke fordeler. Noen av de viktigste fordelene er: - Styrt tjeneste: Tar byrd… |
| 11 | `f2c87d24c5be` | `/nb/broker/about/index.md` | Altinn Formidling gir styrt filoverføring (Managed File Transfer - MFT), med sikker overføring av store filer fra én avsender til en eller flere mottakere. Styrt filoverføring gir flere fordeler sam… |
| 12 | `5c9c216c8c1f` | `/nb/broker/broker-transition/technical-overview/index.md` | Når Altinn 3 Overgangsløsning for formidlingstjeneste funksjonalitet er aktivert i Altinn 2, kan du forvente følgende: 1. Tjenesteeiere kan be om at Altinn 2 Formidlingstjenester blir overført til Al… |
| 13 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 14 | `f731ee7ddee5` | `/nb/authorization/reference/architecture/accesscontrol/pip/index.md` | --- title: Policy Informasjonspunkt description: Policy Informasjonspunkt(er) er ansvarlige for å gi nødvendig informasjon til konteksthåndtereren slik at den kan berike kontekstforespørselen. diataxi… |
| 15 | `66aa09439e8b` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Alle kan se på datamodellene til en organisasjon som standard. For å få tilgang til å redigere datamodellene må brukeren være del av _Datamodels_-teamet i organisasjonen. Se [tilgangsstyring][3] for m… |
| 16 | `2850f051d81a` | `/nb/authorization/what-do-you-get/index.md` | --- title: Hva får du? description: Altinn Autorisasjon tilbyr komponenter og tjenester som kan benyttes av offentlige virksomheter, systemleverandører/sluttbrukersystemer, virksomheter og innbyggere … |
| 17 | `588e7f66c44f` | `/nb/authorization/getting-started/terms/index.md` | Avgiver/aktør er den innbyggeren eller virksomheten som den man opptrer på vegne av i Altinn. - Rapporterer inn data for - Leser meldinger for - Administrerer rettigheter for Se også part. Å gi ful… |
| 18 | `65ac88e194a6` | `/nb/authorization/about/index.md` | --- title: Om Altinn Autorisasjon description: Altinn Autorisasjon styrer hvem som skal kunne gjøre hva med hvilke data i det offentlige og i samspillet mellom offentlig og privat. aliases: [/technolo… |
| 19 | `6ea4d4ac1d99` | `/nb/correspondence/index.md` | --- title: Altinn 3 Melding description: Altinn 3 Melding ('Correspondence' på engelsk) er en meldingstjeneste for sikker utveksling av korrespondanse, som offisielle brev, varsler og andre dokumenter… |
| 20 | `1df131e9cda4` | `/nb/correspondence/about/index.md` | --- title: Om Altinn Melding description: Altinn Melding er en sikker og effektiv digital tjeneste som gjør det mulig for offentlige virksomheter å sende meldinger til innbyggere, næringsliv og andre … |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

## altinn-authorization-accessgroups-knytning

**Query:** Hvordan knytter man en organisasjon til en tilgangsgruppe i registeret?

**Family:** `:navigational`  |  **Target doc (inventory):** /nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md

**Retrieval:** phrase=59 metadata=0 content=18 merged=67  |  auto-filter=none

| rank | chunk_id | url | snippet |
|---|---|---|---|
| 1 | `a438b1775bf0` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | --- title: Fullmakter fra Enhetsregisteret som knytter virksomheter sammen description: Virksomheter som tildeles roller i Enhetsregisteret kan også få fullmakter på vegne av virksomheten i Altinn. He… |
| 2 | `05a9b157e8fd` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` | Organisasjoner i Altinn Studio eier applikasjonene og gjør det mulig for flere innen samme organisasjon å samarbeide. For å bli del av en organisasjon må en administrator for din organisasjon gi deg … |
| 3 | `989cc83e5afc` | `/nb/broker/index.md` | --- title: Altinn 3 Formidling description: Altinn 3 Formidling ('Broker' på engelsk) tilbyr styrt filoverføring med støtte for store filer og avansert funksjonalitet for informasjonssikkerhet, status… |
| 4 | `434ca284c0bf` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` | --- title: Klientdelegering description: Denne veiledningen viser deg hvordan du som sluttbruker kan legge til klienter på en systemtilgang for kunder. diataxis: diataxis_how-to-guides draft: false is… |
| 5 | `3e49a16a027b` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Altinn nøster fullmakter kun i ett ledd. Eksempel på hvordan det fungerer: - "Bergen AS" registrerer "Trondheim AS" i rollen som daglig leder - Daglig leder for "Trondheim AS" er "Oslo AS" - Ola er… |
| 6 | `9584a6e89afc` | `/nb/broker/broker-transition/getting-started/index.md` | For å bruke Formidling overgangsløsning i Altinn for å opprette, laste opp og hente fil metadata, må en tjenesteeier fullføre følgende trinn. 1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha… |
| 7 | `ac4f2dd7a303` | `/nb/broker/broker-transition/getting-started/index.md` | --- title: Komme i gang med Formidling Overgangsløsning descriptions: Hvordan komme i gang med å bruke Formidling overgangsløsningen fra Altinn 2 til Altinn 3 draft: false iscjklanguage: false lastmod… |
| 8 | `3222561ce68f` | `/nb/broker/broker-transition/usage/index.md` | --- title: Bruksanvisning description: Hvordan bruke Altinn 3 Formidling overgangsløsningen. draft: false iscjklanguage: false lastmod: 2024-08-12 11:10:29 +0200 CEST linktitle: Bruksanvisning product… |
| 9 | `bbf82de07a2f` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | --- title: Godkjenne systemtilgang description: Denne veiledningen viser deg hvordan du som sluttbruker kan godkjenne en systemtilgang du har fått fra en fagsystem-leverandør. diataxis: diataxis_how-t… |
| 10 | `14d29a52b3e3` | `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` | --- title: Altinn Autorisering description: Teknisk oversikt over hvordan Dialogporten integrerer med Altinn Autorisering diataxis: diataxis_reference draft: false iscjklanguage: false lastmod: 2025-1… |
| 11 | `ec3d8182194e` | `/nb/altinn-studio/v8/guides/design/insight/index.md` | --- title: Innsiktsfase description: Først av alt bør du samle prosjektgruppen og starte med å definere problemet. Hva er det dere skal løse og hvem skal det løses for? diataxis: diataxis_how-to-guide… |
| 12 | `a12ebb8106c9` | `/nb/events/reference/architecture/events/index.md` | -Bygging av Azure Function zip-filen gjøres i en [Azure DevOps Pipeline](https://dev.azure.com/brreg/altinn-studio/_build?definitionId=244) - Distribusjon av Azure Functions gjøres i en [Azure DevOps… |
| 13 | `a548f91970c3` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` | Det er tilknyttet virksomhet og personer reigstrert med nøkkelroller i denne som får fullmakter på vegne av den aktuelle virksomheten. I tabeller på [denne siden](/nb/authorization/what-do-you-get/acc… |
| 14 | `9f75a7784e9b` | `/nb/dialogporten/index.md` | --- title: Dialogporten description: Dialogporten er en løsning som tillater meldinger og dialoger implementert på Altinn 3 og andre digitale tjenesteplattformer tilgjengelig for sluttbruker-systemer … |
| 15 | `58c4de742356` | `/nb/authorization/what-do-you-get/authentication/index.md` | Accepted providers include: `maskinporten` and `id-porten`. Request must include a bearer token in the authorization header. Set test equal to true if retrieving a token for Testdepartementet. (This o… |
| 16 | `d2ff39fc153a` | `/nb/authorization/reference/architecture/accesscontrol/prp/index.md` | --- title: Policy Retrieval Point description: Policy Retrieval Point er funksjonaliteten der Policy Decision Point (PDP) henter policyen som er definert for en appressurs. diataxis: diataxis_referenc… |
| 17 | `9b8c263e00d1` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | Klikk på Slett-knappen til høyre for feltnavnet. 1. Klikk på noden/feltet du ønsker å redigere for å få opp egenskaper i høyre-panelet 2. Rediger egenskapene for noden/feltet som ønsket. Tilgjengelig… |
| 18 | `67bb01b2b806` | `/nb/authorization/guides/system-vendor/system-user/systemregistration/index.md` | --- title: Registrering av system description: Denne veiledningen beskriver hvordan du som sluttbrukersystemleverandør registrerer et system i systemregisteret. diataxis: diataxis_how-to-guides draft:… |
| 19 | `d4be96620198` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` | 1. Her godkjenner en sluttbruker en forespørsel om å opprette en Systemtilgang for klienter. For å godkjenne en systemtilgang-forespørsel må innlogget bruker ha rollen Tilgangsstyrer i valgt organisas… |
| 20 | `35aa8782432a` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` | For å legge til felt på øverste nivå (rot-node), klikk "Legg til" (**1** i bildet under). Du kan legge til underfelt på felter av type Objekt ved å klikke på **+**-knappen til høyre for feltnavnet, e… |

**Annotation:** keep | drop | edit-query "<new query>"

**Goldens:**

**Notes:**

---

