// Does the proxy actually stream, or does it just eventually deliver?
//
// A buffering proxy is not broken in any way you can see from the outside. The
// client gets every byte and the right answer; it simply gets them all at the
// end, and the app looks slow rather than wrong. So "I received the events" is
// not evidence of streaming. Arrival TIMES are.
//
// This measures inter-frame arrival times through the real proxy, and — this is
// the part that makes it a measurement rather than a hope — through a
// deliberately BUFFERING proxy on the same upstream, in the same run. If the
// two produce the same verdict, the instrument is inert and its "STREAMING"
// means nothing. Reporting a number that cannot come out wrong is worse than
// reporting none.
//
// Run:
//   node proxy.mjs &                 # the real one, port 8787
//   node check-streaming.mjs         # starts its own control on 8788
//
// Reads no key: it talks to the proxy, exactly as a browser would.

import http from 'node:http';

const REAL = `http://localhost:${process.env.PORT ?? 8787}/api/chat/stream`;
const CONTROL_PORT = 8788;
const API_BASE = process.env.DIGDIR_API_BASE ?? 'http://localhost:8081';
const API_KEY = process.env.DIGDIR_API_KEY;
const QUERY = process.env.CHECK_QUERY ?? 'Hva er Digdir?';

// --- the control: the anti-pattern, on purpose --------------------------------
// Identical to the real proxy except for one line: it awaits the whole upstream
// body before writing anything. This is the mistake the real proxy exists to
// avoid, and it is here so the checker has something it must report differently.
const control = http.createServer(async (req, res) => {
  const upstream = await fetch(`${API_BASE}/api/mcp`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
      'MCP-Protocol-Version': '2026-07-28',
      'Mcp-Method': 'tools/call',
      'Mcp-Name': process.env.DIGDIR_TOOL ?? 'builtin.agent-rag-agent__agent-rag-graph-bundled',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'tools/call',
      params: {
        name: process.env.DIGDIR_TOOL ?? 'builtin.agent-rag-agent__agent-rag-graph-bundled',
        arguments: {
          'user-query': QUERY,
          tenant: process.env.DIGDIR_TENANT ?? 'digdir',
          dataset_config_key: process.env.DIGDIR_DATASET_CONFIG_KEY ?? 'public-docs',
        },
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2026-07-28',
          progressToken: `control-${Date.now()}`,
        },
      },
    }),
  });
  const whole = await upstream.text();   // <-- THE BUG, deliberately
  res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' });
  res.end(whole);
});

/** Hit `url`, timestamp every SSE frame as it lands. */
async function timeFrames(url, label) {
  const t0 = Date.now();
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: QUERY }),
  });
  if (!res.ok || !res.body) {
    const body = await res.text().catch(() => '');
    throw new Error(`${label}: HTTP ${res.status} ${body.slice(0, 300)}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const arrivals = [];
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    const at = Date.now() - t0;
    buf += decoder.decode(value, { stream: true });
    // Count completed SSE frames, and stamp each with when its bytes arrived.
    let idx;
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const frame = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      if (frame.trim()) arrivals.push(at);
    }
  }
  return { label, total: Date.now() - t0, arrivals };
}

function report({ label, total, arrivals }) {
  const n = arrivals.length;
  if (n === 0) return { label, verdict: 'NO FRAMES', total, n };
  const first = arrivals[0];
  const last = arrivals[n - 1];
  const spread = last - first;
  const gaps = arrivals.slice(1).map((t, i) => t - arrivals[i]);
  const bigGaps = gaps.filter((g) => g >= 200).length;

  console.log(`\n── ${label} ──`);
  console.log(`  frames                : ${n}`);
  console.log(`  total elapsed         : ${total} ms`);
  console.log(`  first frame at        : ${first} ms  (${((first / total) * 100).toFixed(1)}% of the way through)`);
  console.log(`  last frame at         : ${last} ms`);
  console.log(`  spread first→last     : ${spread} ms`);
  console.log(`  gaps >= 200ms         : ${bigGaps} of ${gaps.length}`);
  console.log(`  arrival times (ms)    : ${arrivals.join(', ')}`);

  // Streaming means frames were spread across the run, not delivered together
  // at the end. Both conditions, so a slow single-frame response cannot pass.
  const streaming = spread >= 500 && bigGaps >= 1;
  return { label, verdict: streaming ? 'STREAMING' : 'BUFFERED', total, n, first, spread, bigGaps };
}

if (!API_KEY) {
  console.error('DIGDIR_API_KEY must be set for the control proxy (the real proxy holds its own).');
  process.exit(1);
}

await new Promise((r) => control.listen(CONTROL_PORT, r));
try {
  const realR = report(await timeFrames(REAL, `REAL proxy (proxy.mjs, streams through)`));
  const ctrlR = report(await timeFrames(`http://localhost:${CONTROL_PORT}/`, `CONTROL (awaits whole body — the bug)`));

  console.log('\n──────── verdict ────────');
  console.log(`  real proxy : ${realR.verdict}`);
  console.log(`  control    : ${ctrlR.verdict}`);

  if (realR.verdict === ctrlR.verdict) {
    console.log('\n  INSTRUMENT INERT: both sides scored the same, so this check cannot');
    console.log('  tell a streaming proxy from a buffering one and proves nothing.');
    process.exit(2);
  }
  if (realR.verdict !== 'STREAMING') {
    console.log('\n  FAIL: the real proxy did not stream.');
    process.exit(1);
  }
  console.log('\n  PASS: the real proxy streamed, and the control did not — so the');
  console.log('  measurement distinguishes the two states rather than always saying yes.');
} finally {
  control.close();
}
