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
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.diffbased.common.DiffBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

// debug_codeByHash returns the contract bytecode whose keccak256 equals the given code hash.
// op-geth/op-reth implement this; op-succinct's kona witness generation calls it to fetch
// code preimages. Besu didn't expose it. Works on Bonsai's default code-hash storage strategy,
// where code is keyed by its hash (the account hash is unused for that lookup).
public class DebugCodeByHash implements JsonRpcMethod {

  private final WorldStateArchive worldStateArchive;

  public DebugCodeByHash(final WorldStateArchive worldStateArchive) {
    this.worldStateArchive = worldStateArchive;
  }

  @Override
  public String getName() {
    return RpcMethod.DEBUG_CODE_BY_HASH.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext request) {
    final Hash codeHash;
    try {
      codeHash = request.getRequiredParameter(0, Hash.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid code hash parameter (index 0)", RpcErrorType.INVALID_PARAMS, e);
    }

    final Bytes code = lookupCode(codeHash).orElse(Bytes.EMPTY);
    return new JsonRpcSuccessResponse(request.getRequest().getId(), code.toHexString());
  }

  private Optional<Bytes> lookupCode(final Hash codeHash) {
    if (worldStateArchive instanceof DiffBasedWorldStateProvider diffBased
        && diffBased.getWorldStateKeyValueStorage()
            instanceof BonsaiWorldStateKeyValueStorage bonsai) {
      // Under the code-hash storage strategy the account hash is ignored.
      return bonsai.getCode(codeHash, Hash.ZERO);
    }
    return Optional.empty();
  }
}
