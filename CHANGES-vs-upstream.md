# op-besu-ssv — changes vs upstream `optimism-java/op-besu`

Fork base: `53e891a729dc17fcc85bfb157c17bf0f1722da5a` (branch `op-stack`, Besu 24.5.6).
Branch: `feat/holocene-isthmus`. Head: `5b44baca2`.

**Goal:** make op-besu (Java OP Stack execution client) usable in the Ethera local-testnet as the
rollup-boost `L2_URL` validator EL in place of op-reth — adding the Holocene **and Isthmus** forks,
the L1-reorg recovery behaviour OP needs, and the full RPC surface op-succinct's kona witness
generation requires.

**Result:** op-besu runs a complete **Isthmus** chain (Prague EVM + final EIP-7685 + EIP-2935) as
the rollup-boost validator EL. Both rollups produce blocks continuously with **zero state-root
mismatches**, the genesis hash is byte-identical to op-reth/op-geth, real txs (L2 contract deploys)
land, and op-batcher submits batches to L1. op-besu also serves the entire op-succinct witness
path: against op-besu at Isthmus, **mock range proofs execute genuinely** (`0→10→20→30`, ~15s each,
zero deserialize/panic/UnexpectedEof), **aggregation (0–30) executes**, and the **L2 output is
proposed to L1** (`latest_proposed_block=30`). (Earlier docs described aggregation panicking under
mock — that was a stale op-succinct range ELF, fixed on the op-succinct side, not op-besu.)

Isthmus is fully working — see section F. The only OP-Isthmus feature not implemented is the
**operator fee**, which is `0` on the local and stage chains, so it is a no-op there (it would only
matter if `operatorFeeScalar`/`operatorFeeConstant` were set non-zero).

To view a file's real diff: `git diff 53e891a729..HEAD -- <path>` or open the commit by hash.

## Commits

| Hash | Summary |
|------|---------|
| `f7a73aa11` | feat: add Holocene + Isthmus OP forks (Prague EVM base) |
| `8891ff5f7` | feat(isthmus): set empty-requests-hash on Isthmus genesis header |
| `b480b07ee` | feat(holocene): accept eip1559Params in engine payload attributes |
| `415946c58` | fix(op): enable OP-fork block production/validation (deposit receipts + getPayload registration) |
| `428d15c0f` | feat(isthmus): genesis withdrawalsRoot = L2ToL1MessagePasser storage root |
| `9723875f6` | feat(isthmus): Engine API V4 + block creation OP-Isthmus reconciliation (WIP) |
| `5d311b873` | fix(isthmus): don't apply EIP-2935 history write (OP Isthmus omits it) |
| `cf2ad2788` | fix(merge): honor CL forkchoice rewind to ancestor head on OP Stack |
| `b6aee5d27` | fix(rpc): accept block hash in debug_getRawHeader/getRawBlock |
| `bd09dceec` | feat(rpc): implement debug_codeByHash for op-succinct witness preimages |
| `45674df01` | feat(rpc): implement debug_dbGet for op-succinct code preimages |
| `1a8868f41` | fix(rpc): eth_getProof returns exclusion proof for absent accounts |
| `5b44baca2` | feat(isthmus): complete Isthmus block processing, engine API, and RPC (see §F) |

Diff stat vs base: **31 files, +825 / −53** (4 new Java files, 26 modified; build: `Dockerfile.local`,
`build.gradle`).

---

## A. Fork recognition & schedule (Holocene + Isthmus) — `f7a73aa11`

| File (under each module's `…/java/`) | Lines | What |
|---|---|---|
| `org/hyperledger/besu/datatypes/HardforkId.java` | L178 | add `HOLOCENE`, `ISTHMUS` to `OptimismHardforkId` |
| `org/hyperledger/besu/config/GenesisConfigOptions.java` | L382-417 | `getHoloceneTime`/`isHolocene`/`getIsthmusTime`/`isIsthmus` |
| `org/hyperledger/besu/config/JsonGenesisConfigOptions.java` | L432-471 | impls (read `holoceneTime`/`isthmusTime`) |
| `org/hyperledger/besu/config/StubGenesisConfigOptions.java` | L59-66, L345-370 | test-stub fields/overrides |
| `org/hyperledger/besu/ethereum/mainnet/ProtocolScheduleBuilder.java` | L359-373 | register Holocene + Isthmus milestones |
| `org/hyperledger/besu/ethereum/mainnet/MainnetProtocolSpecFactory.java` | L257-284 | `holoceneDefinition`/`isthmusDefinition` factory entries |
| `org/hyperledger/besu/ethereum/mainnet/MainnetProtocolSpecs.java` | L1257-1346 | spec bodies (Holocene→Granite; Isthmus = Prague EVM + `populateForIsthmus` + msg-passer withdrawals validator) |

## B. Isthmus precompiles / header / engine / block-creation — `8891ff5f7`, `428d15c0f`, `9723875f6`, `5d311b873`

| File | Lines | What |
|---|---|---|
| `org/hyperledger/besu/evm/precompile/MainnetPrecompiledContracts.java` | L222-262 | `isthmus()`/`populateForIsthmus` (Granite OP precompiles + Prague BLS) |
| `org/hyperledger/besu/ethereum/mainnet/MainnetPrecompiledContractRegistries.java` | L23, L84-96 | wire Isthmus registry |
| `org/hyperledger/besu/ethereum/mainnet/WithdrawalsValidator.java` | L97-129 | `MessagePasserStorageRootWithdrawals` |
| `org/hyperledger/besu/ethereum/chain/GenesisState.java` | L19, L124, L147, L207-246, L260, L376 | genesis `requestsHash=sha256("")` + `withdrawalsRoot` = L2ToL1MessagePasser storage root |
| `…/api/jsonrpc/internal/parameters/EnginePayloadParameter.java` | L41, L96, L118, L181 | add `withdrawalsRoot` field/getter |
| `…/api/jsonrpc/internal/methods/engine/AbstractEngineNewPayload.java` | L283-304 | Isthmus withdrawalsRoot + requestsHash on newPayload |
| `…/api/jsonrpc/internal/methods/engine/EngineNewPayloadV4.java` | L69, L79 | skip deposit-field + PRAGUE fork check for OP |
| `…/api/jsonrpc/internal/results/EngineGetPayloadResultV4.java` | L100, L167, L275 | serialize withdrawalsRoot |
| `org/hyperledger/besu/ethereum/blockcreation/AbstractBlockCreator.java` | L17, L327-345, L483-496 | build Isthmus headers (mp storage root + sha256 requestsHash) |

> Isthmus status: header/Engine-API/genesis reconciled, but block **execution** diverges because
> Besu 24.5.6 uses the pre-final EIP-7685 encoding (MPT requestsRoot + body requests) incompatible
> with OP-Isthmus (sha256 requestsHash, empty body). Deferred; chain runs Holocene.

## C. Holocene engine driveability — `b480b07ee`, `415946c58`

| File | Lines | What |
|---|---|---|
| `…/api/jsonrpc/internal/parameters/EnginePayloadAttributesParameter.java` | L22, L37, L49, L60, L95, L122 | accept `eip1559Params` (made FCU return VALID at Holocene) |
| `org/hyperledger/besu/ethereum/api/jsonrpc/methods/ExecutionEngineJsonRpcMethods.java` | L149-167, L172-181 | register getPayloadV3/V4 for OP fork names |

## D. L1-reorg sequencer wedge fix — `cf2ad2788`

| File | Lines | What |
|---|---|---|
| `org/hyperledger/besu/consensus/merge/blockcreation/MergeCoordinator.java` | L617-630 (`updateForkChoice`) | skip Besu's `IGNORE_UPDATE_TO_OLD_HEAD` heuristic when `mergeContext.isOptimism()` — so a CL forkchoice rewind to an ancestor head (after an L1 reorg) rewinds + builds like op-geth/op-reth instead of returning a null payloadId (which wedged the sequencer) |

## E. op-succinct witness-generation RPC surface — `b6aee5d27`, `bd09dceec`, `45674df01`, `1a8868f41`

| File | Lines | What |
|---|---|---|
| `…/api/jsonrpc/internal/methods/DebugGetRawHeader.java` | rewritten, L42-72 | accept block **hash** (extend `AbstractBlockParameterOrBlockHashMethod`) |
| `…/api/jsonrpc/internal/methods/DebugGetRawBlock.java` | rewritten, L41-62 | accept block **hash** |
| `…/api/jsonrpc/internal/methods/DebugCodeByHash.java` | new (73 lines) | `debug_codeByHash` — code by hash (reth-compatible; kona ultimately uses debug_dbGet) |
| `…/api/jsonrpc/internal/methods/DebugDbGet.java` | new (93 lines) | `debug_dbGet` — code preimages by geth key (`0x63`+codehash or bare hash) → bytecode from Bonsai |
| `org/hyperledger/besu/ethereum/proof/WorldStateProofProvider.java` | L18 (import Wei), L74-86 (`getAccountProof`) | **exclusion proof** for absent accounts: zeroed `StateTrieAccountValue(0, Wei.ZERO, EMPTY_TRIE_HASH, EMPTY)` + the already-computed proof nodes, instead of returning empty → `eth_getProof` no longer errors `-32000 Account not found` |
| `org/hyperledger/besu/ethereum/api/jsonrpc/RpcMethod.java` | L54-55 | `DEBUG_CODE_BY_HASH`, `DEBUG_DB_GET` |
| `org/hyperledger/besu/ethereum/api/jsonrpc/methods/DebugJsonRpcMethods.java` | L22-23 (imports), L123-124 | register both new methods |

> Why these: kona's preimage oracle fetches headers/blocks/receipts **by hash** (debug_getRaw*),
> code via **debug_dbGet** (geth raw-DB key semantics), and account/state via **eth_getProof**
> (including exclusion proofs for not-yet-created accounts). All op-geth/op-reth support these;
> trie nodes go via eth_getProof, so Bonsai's lack of node-by-hash is not hit.

## F. Full Isthmus: block processing + engine API + RPC — `5b44baca2`

Genesis parity at Isthmus already held (op-besu derives the op-reth/op-geth genesis hash
byte-identical: `requestsHash=sha256("")` via `8891ff5f`, `withdrawalsRoot`=L2ToL1MessagePasser
storage root via `428d15c0`). The remaining divergences were all at **block-1+ processing**, found
empirically by deploying at Isthmus and reading each failure. Five fixes:

| File | Lines | What |
|---|---|---|
| `…/mainnet/requests/OptimismRequestsValidatorCoordinator.java` | new (95 lines) | OP Isthmus requests validator: accepts a header `requestsHash == sha256("")` (`0xe3b0c4…b855`) with no body/processed requests, and rejects anything else. The stock `RequestsValidatorCoordinator.empty()` rejects **any** header requestsRoot ("must not contain requests root"), which rejected every Isthmus block. |
| `…/mainnet/requests/RequestsValidatorCoordinator.java` | ctor | `private` → `protected` so the OP coordinator can subclass it. |
| `…/mainnet/MainnetProtocolSpecs.java` (`isthmusDefinition`) | `.requestsValidator(…)` | wire `OptimismRequestsValidatorCoordinator.isthmus()`. |
| `…/mainnet/MainnetProtocolSpecs.java` (`isthmusDefinition`) | `.blockHashProcessor(…)` | **EIP-2935**: OP Isthmus *does* adopt it (the history contract is deployed at genesis and op-reth writes it every block). Wire `PragueBlockHashProcessor(0x0000F90827F1C53a10cb7A02335B175320002935, 8191)` — Besu 24.5.6's default is the *draft* address `0x0aae…f91e` + window 8192; final/OP uses `0x…2935` + an 8191 ring buffer (`slot=(number-1)%8191`, confirmed from the deployed bytecode). Without this the world-state root diverges at block 1. (Reverses the earlier `5d311b87` "OP omits 2935" note, which was a Holocene-era observation.) |
| `…/api/jsonrpc/internal/results/EngineGetPayloadResultV4.java` | envelope | add top-level `parentBeaconBlockRoot` (as V3 has) **and** `executionRequests` (`[]` for OP). rollup-boost calls `engine_getPayloadV4` on both the builder and the L2 (op-besu); op-node requires both fields to seal, so their absence wedged block production. |
| `…/api/jsonrpc/internal/results/BlockResult.java` | requestsHash field/getter | `eth_getBlock*` now emits geth-named `requestsHash` (= header requestsRoot, `sha256("")` for Isthmus). op-batcher (Go) and kona (Rust) **recompute** the block hash from the JSON header, so omitting it made them derive a wrong hash → op-batcher looped "block does not extend existing chain" → no batches → safe head stuck at 0. |

> Not implemented: the OP-Isthmus **operator fee**. It is `0` on the local and stage chains
> (`operatorFeeScalar`/`operatorFeeConstant = 0` in the op-deployer intent), so it is a no-op there;
> a tx-bearing block would only diverge if an operator set it non-zero.

## Build

| File | What |
|---|---|
| `op-besu/Dockerfile.local` | native arm64 multi-stage build (Temurin-21 compile → Ubuntu openjdk-21-jre runtime). The published ghcr arm64 image SIGILLs (misbuilt JRE). |
| `build.gradle` | removed `-Werror` (the `op-stack` branch carries WildcardImport warnings) |

## The 8 fixes that make op-besu op-succinct-capable

Code fixes in this repo (need a fresh sync from genesis after each — op-besu can't backfill
mid-chain with P2P disabled): (5) `debug_getRawHeader/Block` by-hash, (6) `debug_codeByHash`,
(7) `debug_dbGet` code preimages, (8) `eth_getProof` exclusion proof.

Runtime flags / external config (set by the local-testnet overlays, not this repo):
1. `--bonsai-historical-block-limit=100000` + `--bonsai-trie-logs-pruning-window-size=120000`
   (op-succinct queries deep historical state; Besu default window is 512)
2. op-batcher `OP_BATCHER_THROTTLE_UNSAFE_DA_BYTES_LOWER_THRESHOLD=0` (op-besu lacks
   `miner_setMaxDASize`, so DA throttling must be disabled or the batcher shuts down)
3. `--rpc-http-max-active-connections=2000` (op-succinct fires ~100+ concurrent witness requests;
   Besu default is 80)
4. op-succinct `SAFE_DB_FALLBACK=true` (op-node here runs without SafeDB enabled)

Other op-besu runtime needs: `--engine-jwt-disabled`, `--data-path=/opt/besu/data`.
