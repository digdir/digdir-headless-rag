# Pipeline + Skills Conceptual Simplification Recommendations

## Quick Assessment
The current system exposes multiple overlapping execution models and initialization/registry paths, which increases conceptual surface area:
- Legacy RAG state machine pipeline in `server/src/digdir/rag/core.cljc` (`rag-pipeline`, `retrieval-pipeline`)
- Skills graph runner in `server/src/digdir/skills/graph/runner.clj`
- Skill graph storage in `server/src/digdir/skills/templates/*`
- Skills registry and core schema in `server/src/digdir/rag/skills/core.clj`
- Skills initialization in `server/src/digdir/skills/api.clj` and `server/src/digdir/skills/init.clj`

## Recommendations

1. Unify to one execution model
- Prefer skills graphs as the single conceptual execution model.
- Keep `rag-pipeline` as a thin adapter that calls a skill graph and converts outputs until all call sites are migrated.

2. Collapse initialization + registry into a single module
- Consolidate init/reset/list/get/tool-definitions into a single skills system namespace.
- Have all callers delegate to it to eliminate duplicate init state.

3. Make parameter resolution declarative and centralized
- Introduce a single resolver that maps `skill-id` + metadata + skill graph config -> resolved parameters.
- Ensure `build-execution-context` always uses it so all skills get consistent configuration.

4. Standardize the graph I/O contract
- Choose a canonical vocabulary (e.g., `:query`, `:chunks`, `:context-docs`, `:response`, `:search-phrases`).
- Restrict translation to the API boundary or legacy adapter.

5. Collapse retrieval and multi-retrieval into a single skill
- One retrieval skill that accepts one or many queries, with optional merge strategy and limits.
- Optionally keep multi-retrieval as a thin wrapper, or remove it.

6. Make services a real dependency boundary
- Resolve service clients/configs once in context, and only use `services` inside skills.
- Avoid direct `cfg/get` calls inside skills.

7. Simplify tool definition generation
- Store tool definitions in skill metadata or generate them from metadata.
- List tools from the registry instead of maintaining manual lists and `case` statements.

8. Align docs to actual implementation
- Update `SKILLS_README.md` to reflect the skills registry, graph runner, and skill graphs that exist now.
- Remove references to outdated phases and file locations.

## One-Line Concept
Pipeline = ingestion/materialization config; skill graph = runtime execution. Skills are pure functions with parameters resolved from config and dependencies injected via services.
