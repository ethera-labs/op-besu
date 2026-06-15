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
package org.hyperledger.besu.ethereum.mainnet.requests;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.RequestType;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.Request;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableSortedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests validator for OP Stack Isthmus.
 *
 * <p>OP Isthmus adopts the <em>final</em> EIP-7685 commitment scheme but carries no execution-layer
 * requests: every block commits to an empty requests list via {@code requestsHash = sha256("")},
 * the block body contains no requests, and no requests are processed during execution. (Besu's base
 * mainnet wiring still uses the pre-final encoding — an MPT {@code requestsRoot} over a requests
 * list that must be physically present in the body — so the stock {@link
 * RequestsValidatorCoordinator#empty()} coordinator rejects any header carrying a {@code
 * requestsRoot}, which would reject every Isthmus block.)
 *
 * <p>This coordinator accepts exactly the Isthmus shape: a header {@code requestsRoot} equal to
 * {@code sha256("")}, with no requests anywhere, and rejects anything else.
 */
public class OptimismRequestsValidatorCoordinator extends RequestsValidatorCoordinator {
  private static final Logger LOG =
      LoggerFactory.getLogger(OptimismRequestsValidatorCoordinator.class);

  /** EIP-7685 commitment for an empty requests list: sha256 over zero bytes. */
  public static final Hash EMPTY_REQUESTS_HASH =
      Hash.fromHexString("0xe3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

  /**
   * @return a coordinator that validates the OP Isthmus empty-requests commitment.
   */
  public static RequestsValidatorCoordinator isthmus() {
    return new OptimismRequestsValidatorCoordinator();
  }

  private OptimismRequestsValidatorCoordinator() {
    super(ImmutableSortedMap.<RequestType, RequestValidator>of());
  }

  @Override
  public boolean validate(
      final Block block,
      final Optional<List<Request>> maybeRequests,
      final List<TransactionReceipt> receipts) {
    final Hash blockHash = block.getHash();

    final Optional<Hash> maybeRequestsRoot = block.getHeader().getRequestsRoot();
    if (maybeRequestsRoot.isEmpty()) {
      LOG.warn("Block {} (Isthmus) must contain the empty-requests hash", blockHash);
      return false;
    }
    if (!EMPTY_REQUESTS_HASH.equals(maybeRequestsRoot.get())) {
      LOG.warn(
          "Block {} (Isthmus) requests hash {} does not match the empty-requests hash {}",
          blockHash,
          maybeRequestsRoot.get(),
          EMPTY_REQUESTS_HASH);
      return false;
    }

    // OP Isthmus carries no execution-layer requests in the body and processes none.
    if (block.getBody().getRequests().map(r -> !r.isEmpty()).orElse(false)) {
      LOG.warn("Block body {} (Isthmus) must not contain requests", blockHash);
      return false;
    }
    if (maybeRequests.map(r -> !r.isEmpty()).orElse(false)) {
      LOG.warn("Block {} (Isthmus) must not process requests", blockHash);
      return false;
    }
    return true;
  }
}
