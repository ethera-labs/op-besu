# op-besu EL spec (for stage deployment)

The **complete, self-contained run spec** for op-besu as the rollup-boost validator execution
client (the `L2_URL` EL, replacing op-reth/op-geth). This is the exact configuration validated
end-to-end on **local-testnet** — full Isthmus, JWT-enforced Engine API, op-succinct (mock +
real SP1 network proofs), AltDA, L1↔L2 deposits, ERC-4337 AA, and cross-chain composability all
green on both rollups.

> Use this set **as the source of truth** — do NOT translate the op-reth `extraArgs`. op-besu has
> its own complete equivalents here; the op-reth-only flags (`--rollup.disable-tx-pool-gossip`,
> `--rollup.compute-pending-block`, `--proofs-history`, `miner` namespace) do not apply to Besu
> and are intentionally absent — see "What replaces the op-reth flags" below.

## Image

Build from **`ethera-labs/op-besu` branch `stage`** (HEAD includes the Isthmus work + the engine-JWT
fix, op-besu PR #2) using **`docker/Dockerfile`** (the `release.yml` / gradle path). Publish e.g.
`ghcr.io/ethera-labs/op-besu:<tag>`.

- Runtime is `ubuntu:24.04` + stock `openjdk-21-jre-headless` (no bundled JRE).
- **Smoke-test the published amd64 image boots** before wiring it in (the historical arm64 image
  SIGILL'd; stage is amd64 — confirm it runs).

## besu command / flags

```
besu \
  --genesis-file=<GENESIS_JSON> \                      # Besu reads genesis directly; no geth/reth init step
  --data-path=<DATA_DIR_ON_PVC> \                      # mount to the persistent volume (same PVC pattern as op-reth)
  --bonsai-historical-block-limit=100000 \             # serve historical state/proofs for op-succinct (Besu's --proofs-history equivalent)
  --bonsai-trie-logs-pruning-window-size=120000 \      # must exceed the historical-block-limit
  --network-id=<L2_CHAIN_ID> \
  --rpc-http-enabled \
  --rpc-http-host=0.0.0.0 \
  --rpc-http-port=8545 \
  --rpc-http-api=ETH,NET,WEB3,DEBUG,TXPOOL,ADMIN \
  --rpc-http-max-active-connections=2000 \             # op-succinct fires ~100+ concurrent RPCs; Besu default 80 rejects them
  --rpc-http-cors-origins=* \
  --host-allowlist=* \
  --engine-rpc-enabled \
  --engine-rpc-port=8551 \
  --engine-jwt-secret=<JWT_HEX_FILE> \                 # JWT ENFORCED (requires the PR #2 image)
  --engine-host-allowlist=* \
  --rpc-ws-enabled \
  --rpc-ws-host=0.0.0.0 \
  --rpc-ws-port=8546 \
  --rpc-ws-api=ETH,NET,WEB3,DEBUG,TXPOOL \
  --metrics-enabled \
  --metrics-host=0.0.0.0 \
  --metrics-port=9898 \
  --nat-method=NONE \
  --p2p-enabled=true \
  --p2p-host=0.0.0.0 \
  --p2p-port=30303 \
  --discovery-enabled=false \
  --max-peers=10 \
  --node-private-key-file=<NODE_KEY_FILE>              # op-besu's own p2p identity (see below)
```

Placeholders: `<GENESIS_JSON>`, `<DATA_DIR_ON_PVC>`, `<L2_CHAIN_ID>`, `<JWT_HEX_FILE>`,
`<NODE_KEY_FILE>`.

## Two things that must travel WITH the EL command (easy to miss)

1. **Node key file.** Besu has no `--p2p-secret-key-hex`. Write the 32-byte hex secret to a file
   and point `--node-private-key-file` at it. On local-testnet this is done in the entrypoint:

   ```sh
   printf '0x%s' "$P2P_SECRET_HEX" > /tmp/op-besu-nodekey
   exec besu ... --node-private-key-file=/tmp/op-besu-nodekey
   ```

   Use the same deterministic secret the builder's trusted-peer enode is derived from, so op-besu's
   enode matches what the rbuilder dials.

2. **Batcher DA-throttle off.** op-besu has no `miner_setMaxDASize` (no `miner` namespace). Set on
   the **op-batcher** (not the EL): `OP_BATCHER_THROTTLE_UNSAFE_DA_BYTES_LOWER_THRESHOLD=0`.
   Without this the batcher logs "SetMaxDASize RPC method unavailable … shutting down" and stops,
   so no batches reach L1 and the safe head never advances.

## What replaces the op-reth flags (so dropping them is safe)

| op-reth flag (stage) | op-besu | effect of "dropping" it |
|---|---|---|
| `--disable-discovery` | `--discovery-enabled=false` | replaced — same behavior |
| `--max-peers=10` | `--max-peers=10` | same |
| `--nat=none` | `--nat-method=NONE` | replaced — same behavior |
| `--p2p-secret-key-hex=…` | `--node-private-key-file=…` | replaced — same enode identity |
| `--proofs-history` (+ storage-path) | `--bonsai-historical-block-limit` (+ pruning window) | **substituted — do NOT just drop**; needed by op-succinct |
| `--rollup.disable-tx-pool-gossip` | (none) | safe to drop — op-besu gossiping txs is harmless (builder ingests via RPC) |
| `--rollup.compute-pending-block` | (none) | safe to drop — op-besu doesn't need it |
| api `…,miner` | api `…,ADMIN` (no `miner`) | handled via the batcher-throttle-off env above |

## Peering note (match local-testnet topology)

On local-testnet, **op-rbuilder dials op-besu** (op-besu is the passive peer — it just listens on
`:30303`, no `--trusted-peers`/`--static-nodes`). The stage op-reth config instead has the **EL dial
the rbuilder** (`--trusted-peers=enode://…@…-rbuilder…:30303`).

- If stage's rbuilder dials the EL inbound (as on local-testnet), the spec above works as-is.
- If not, add `--static-nodes-file=<json>` to op-besu, where the JSON lists the **rbuilder's** enode
  (`["enode://<rbuilder-pubkey>@<rbuilder-host>:30303"]`), so op-besu dials out.
- Either way op-besu still produces blocks (canonical blocks arrive over the Engine API); missing
  the peer only leaves the rbuilder peerless, it does not stop block production.

## EL-agnostic (keep the existing stage Helm values unchanged)

`persistence` (PVC), `genesisConfigMapName`/`genesisConfigKey`, `rollupConfigMap*`, `metrics`,
`ingress`, and the JWT secret wiring are all execution-client-agnostic. Only the **image**, the
**`extraArgs`/command** (this set), and the **`rpc.*.api`** lists change vs the op-reth block.

## Open items (dev side, not blocking the above)

- Minor op-besu RPC gaps that may affect explorer/UX (not block production): `eth_call` deducts OP
  fees so `balanceOf` from a zero-balance sender errors; receipts omit OP L1-fee fields. Decide
  fork-fix vs accept.
