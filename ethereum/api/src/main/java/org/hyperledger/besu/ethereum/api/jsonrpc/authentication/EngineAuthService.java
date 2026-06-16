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
package org.hyperledger.besu.ethereum.api.jsonrpc.authentication;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.impl.Codec;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.RoutingContext;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineAuthService implements AuthenticationService {

  private static final Logger LOG = LoggerFactory.getLogger(EngineAuthService.class);
  private static final int JWT_EXPIRATION_TIME_IN_SECONDS = 60;

  public static final String EPHEMERAL_JWT_FILE = "jwt.hex";

  private final JWTAuth jwtAuthProvider;
  // The raw HMAC key used to validate engine JWTs ourselves (see authenticate()), retained so we
  // can bypass vert.x's JWTAuth.authenticate(), whose User.expired() NPEs on some well-formed
  // tokens (e.g. rollup-boost/op-node) — it guards on a claim being present in one location but
  // reads it from another with a null default. That rejected every engine call with 401.
  private byte[] signingKey;

  public EngineAuthService(final Vertx vertx, final Optional<File> signingKey, final Path datadir) {
    final JWTAuthOptions jwtAuthOptions =
        engineApiJWTOptions(JwtAlgorithm.HS256, signingKey, datadir);
    this.jwtAuthProvider = JWTAuth.create(vertx, jwtAuthOptions);
  }

  public String createToken() {
    JsonObject claims = new JsonObject();
    claims.put("iat", System.currentTimeMillis() / 1000);
    return this.jwtAuthProvider.generateToken(claims);
  }

  private JWTAuthOptions engineApiJWTOptions(
      final JwtAlgorithm jwtAlgorithm, final Optional<File> keyFile, final Path datadir) {
    byte[] signingKey = null;
    if (!keyFile.isPresent()) {
      final File jwtFile = new File(datadir.toFile(), EPHEMERAL_JWT_FILE);
      jwtFile.deleteOnExit();
      final byte[] ephemeralKey = Bytes32.random().toArray();
      try {
        Files.writeString(jwtFile.toPath(), Codec.base16Encode(ephemeralKey));
      } catch (IOException ioe) {
        LOG.warn("Unable to write ephemeral jwt key file to {}", jwtFile.toPath().toString());
        LOG.info("JWT KEY: {}", Codec.base16Encode(ephemeralKey));
      }
      signingKey = ephemeralKey;
    } else { // user configured option to use a specified file
      if (keyFile.get().exists()) {
        try {
          final String keyHex = Files.readAllLines(keyFile.get().toPath()).get(0);
          if (keyHex.length() >= 64) {
            signingKey = Bytes.fromHexString(keyHex).toArray();
          } else {
            UnsecurableEngineApiException e =
                new UnsecurableEngineApiException("signing key too short, 256 bits required");
            e.fillInStackTrace();
            throw e;
          }
        } catch (IOException ioe) {
          UnsecurableEngineApiException e =
              new UnsecurableEngineApiException(
                  "Could not read key from " + keyFile.get().toString());
          e.fillInStackTrace();
          e.initCause(ioe);
          throw e;
        }
      } else {
        UnsecurableEngineApiException e =
            new UnsecurableEngineApiException(
                "Could not read key from " + keyFile.get().toString());
        e.fillInStackTrace();
        throw e;
      }
    }
    if (signingKey == null || signingKey.length < 32) {
      UnsecurableEngineApiException e =
          new UnsecurableEngineApiException(
              "Could not read at least 256 bits of key from "
                  + (keyFile.isPresent() ? keyFile.get().toString() : "undefined"));
      e.fillInStackTrace();
      throw e;
    }

    // Retain the raw key so authenticate() can verify tokens directly (HS256 + iat), bypassing
    // vert.x's expiry validation which NPEs on some clients' tokens.
    this.signingKey = signingKey;

    return new JWTAuthOptions()
        .setJWTOptions(new JWTOptions().setIgnoreExpiration(true).setLeeway(5))
        .addPubSecKey(
            new PubSecKeyOptions()
                .setAlgorithm(jwtAlgorithm.toString())
                .setBuffer(Buffer.buffer(signingKey)));
  }

  @Override
  public void handleLogin(final RoutingContext routingContext) {
    LOG.warn("Engine Auth does not support logins, no login handled");
  }

  @Override
  public JWTAuth getJwtAuthProvider() {
    return this.jwtAuthProvider;
  }

  @Override
  public void authenticate(final String token, final Handler<Optional<User>> handler) {
    handler.handle(verifyEngineToken(token));
  }

  @Override
  public boolean isPermitted(
      final Optional<User> optionalUser,
      final JsonRpcMethod jsonRpcMethod,
      final Collection<String> noAuthMethods) {
    return noAuthMethods.contains(jsonRpcMethod.getName()) || optionalUser.isPresent();
  }

  /**
   * Validates an engine-API JWT directly instead of routing through {@link JWTAuth#authenticate},
   * whose {@code User.expired()} NPEs under vert.x 4.5 on otherwise-valid tokens from some
   * consensus clients (rollup-boost / op-node): it guards on a claim being present in one location
   * but reads it from another with a null default, throwing and rejecting every engine call with
   * 401. Per the Engine API authentication spec we verify the HS256 signature with the shared
   * secret and require an {@code iat} claim within 60 seconds — the same contract op-geth and
   * op-reth enforce. Returns an empty Optional (treated as 401) on any verification failure.
   */
  private Optional<User> verifyEngineToken(final String token) {
    if (token == null) {
      return Optional.empty();
    }
    final String[] parts = token.split("\\.");
    if (parts.length != 3) {
      LOG.debug("Rejecting engine token: malformed JWT");
      return Optional.empty();
    }
    try {
      final byte[] expectedSignature =
          hmacSha256(signingKey, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      final byte[] providedSignature = base64UrlDecode(parts[2]);
      if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
        LOG.debug("Rejecting engine token: signature mismatch");
        return Optional.empty();
      }
      final JsonObject payload = new JsonObject(Buffer.buffer(base64UrlDecode(parts[1])));
      final Long iat = payload.getLong("iat");
      if (iat == null || !issuedRecently(iat)) {
        LOG.warn("Rejecting engine token: missing or stale 'iat' ({})", iat);
        return Optional.empty();
      }
      return Optional.of(User.create(payload));
    } catch (final Exception e) {
      LOG.debug("Rejecting engine token: validation error", e);
      return Optional.empty();
    }
  }

  private static byte[] hmacSha256(final byte[] key, final byte[] data) throws Exception {
    final Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  /** Decodes a JWT segment (unpadded base64url). */
  private static byte[] base64UrlDecode(final String segment) {
    final int paddingNeeded = (4 - (segment.length() % 4)) % 4;
    final StringBuilder padded = new StringBuilder(segment);
    for (int i = 0; i < paddingNeeded; i++) {
      padded.append('=');
    }
    return Base64.getUrlDecoder().decode(padded.toString());
  }

  private boolean issuedRecently(final long iat) {
    long iatSecondsSinceEpoch = iat;
    long nowSecondsSinceEpoch = System.currentTimeMillis() / 1000;
    return (Math.abs((nowSecondsSinceEpoch - iatSecondsSinceEpoch))
        <= JWT_EXPIRATION_TIME_IN_SECONDS);
  }
}
