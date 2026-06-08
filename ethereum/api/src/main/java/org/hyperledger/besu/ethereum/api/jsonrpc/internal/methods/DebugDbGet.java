/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.diffbased.common.DiffBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

// debug_dbGet reads a raw value from the client database by key. op-succinct's kona witness
// generation uses it (op-geth/op-reth compatible) to fetch CODE preimages, keyed either by the
// bare code hash or by geth's code key (0x63='c' prefix + code hash). Besu's Bonsai store has a
// different schema, so we serve the cases kona needs: recognise a code-hash key and return the
// bytecode. Non-code keys (e.g. trie-node hashes) are not served — kona fetches state via
// eth_getProof, not debug_dbGet.
public class DebugDbGet implements JsonRpcMethod {

  private static final byte GETH_CODE_PREFIX = 0x63; // 'c'

  private final WorldStateArchive worldStateArchive;

  public DebugDbGet(final WorldStateArchive worldStateArchive) {
    this.worldStateArchive = worldStateArchive;
  }

  @Override
  public String getName() {
    return RpcMethod.DEBUG_DB_GET.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext request) {
    final Bytes key;
    try {
      key = Bytes.fromHexStringLenient(request.getRequiredParameter(0, String.class));
    } catch (JsonRpcParameterException | IllegalArgumentException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid db key parameter (index 0)", RpcErrorType.INVALID_PARAMS, e);
    }

    final Optional<Hash> codeHash = codeHashFromKey(key);
    final Optional<Bytes> code = codeHash.flatMap(this::lookupCode).filter(c -> !c.isEmpty());
    return code.<JsonRpcResponse>map(
            c -> new JsonRpcSuccessResponse(request.getRequest().getId(), c.toHexString()))
        .orElseGet(
            () -> new JsonRpcErrorResponse(request.getRequest().getId(), RpcErrorType.BLOCK_NOT_FOUND));
  }

  // Bare 32-byte key = code hash; 33-byte key with geth 'c' prefix = code key.
  private Optional<Hash> codeHashFromKey(final Bytes key) {
    if (key.size() == Bytes32.SIZE) {
      return Optional.of(Hash.wrap(Bytes32.wrap(key)));
    }
    if (key.size() == Bytes32.SIZE + 1 && key.get(0) == GETH_CODE_PREFIX) {
      return Optional.of(Hash.wrap(Bytes32.wrap(key.slice(1))));
    }
    return Optional.empty();
  }

  private Optional<Bytes> lookupCode(final Hash codeHash) {
    if (worldStateArchive instanceof DiffBasedWorldStateProvider diffBased
        && diffBased.getWorldStateKeyValueStorage()
            instanceof BonsaiWorldStateKeyValueStorage bonsai) {
      return bonsai.getCode(codeHash, Hash.ZERO);
    }
    return Optional.empty();
  }
}
