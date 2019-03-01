/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/** Holder and certificate parser for HTTPS client certificates. */
public class ClientCertificateHandler {

  private static final Pattern privateKeyExtractor =
      Pattern.compile(
          "-----\\w*BEGIN PRIVATE KEY\\w*-----(.*)-----\\w*END PRIVATE KEY\\w*-----",
          Pattern.DOTALL | Pattern.MULTILINE);

  private final HandshakeCertificates handshakeCertificates;
  private final Optional<HostnameVerifier> hostnameVerifier;

  /**
   * Creates an instance of {@link ClientCertificateHandler}
   *
   * @param handshakeCertificates If non-null, client certificates to use for http connections
   * @param hostnameVerifier Used for testing to bypass hostname verification in integration tests.
   *     Should be {@code null} in production use.
   */
  @VisibleForTesting
  public ClientCertificateHandler(
      HandshakeCertificates handshakeCertificates, Optional<HostnameVerifier> hostnameVerifier) {
    this.handshakeCertificates = handshakeCertificates;
    this.hostnameVerifier = hostnameVerifier;
  }

  /** Create a new ClientCertificateHandler based on client tls settings in configuration */
  public static Optional<ClientCertificateHandler> fromConfiguration(
      ArtifactCacheBuckConfig config) {
    Optional<Path> key = config.getClientTlsKey();
    Optional<Path> certificate = config.getClientTlsCertificate();
    boolean required = config.getClientTlsCertRequired();
    return parseHandshakeCertificates(key, certificate, required)
        .map(
            handshakeCertificates ->
                new ClientCertificateHandler(handshakeCertificates, Optional.empty()));
  }

  /**
   * Throw HumanReadableException if required is true
   *
   * @param required whether to throw or do nothing
   * @param cause pass to HumanReadableException
   * @param formatString pass to HumanReadableException
   * @param args pass to HumanReadableException
   * @throws HumanReadableException
   */
  private static void throwIfRequired(
      boolean required, @Nullable Throwable cause, String formatString, Object... args) {
    if (required) {
      throw new HumanReadableException(cause, formatString, args);
    }
  }

  private static void throwIfRequired(boolean required, String formatString, Object... args) {
    throwIfRequired(required, null, formatString, args);
  }

  /**
   * Check whether filePath is set and if the file pointed to is readable
   *
   * @param filePath filePath to check
   * @param label label indicating option used in exceptions thrown
   * @param required whether to throw or ignore failures
   * @throws {@link HumanReadableException} if the option unset of file cannot be read
   */
  private static boolean isFileAvailable(Optional<Path> filePath, String label, boolean required) {
    if (!filePath.isPresent()) {

      throwIfRequired(required, "Required option for %s unset", label);
      return false;
    }
    if (!Files.isReadable(filePath.get())) {
      throwIfRequired(required, "Cannot read %s file %s", label, filePath.get());
      return false;
    }
    return true;
  }

  /**
   * Parse a PEM encoded private key, with the algorithm decided by {@code certificate}
   *
   * @param keyPathOptional The path to a PEM encoded PKCS#8 private key
   * @param certificate The corresponding public key. Used to determine key's algorithm
   * @param required whether to throw or ignore on unset / missing private key
   * @throws {@link HumanReadableException} on issues with private key
   */
  public static Optional<PrivateKey> parsePrivateKey(
      Optional<Path> keyPathOptional, X509Certificate certificate, boolean required) {
    if (!isFileAvailable(keyPathOptional, "private key", required)) {
      return Optional.empty();
    }
    Path keyPath = keyPathOptional.get();
    try {
      String privateKeyRaw = new String(Files.readAllBytes(keyPath), Charsets.UTF_8);
      Matcher matcher = privateKeyExtractor.matcher(privateKeyRaw);
      if (!matcher.find()) {
        throw new HumanReadableException(
            "Expected BEGIN PRIVATE KEY/END PRIVATE KEY header/tailer surrounding base64-encoded PKCS#8 key in %s, but it was not found",
            keyPath);
      }
      PKCS8EncodedKeySpec keySpec =
          new PKCS8EncodedKeySpec(
              Base64.getDecoder().decode(matcher.group(1).replace("\n", "").replace("\r", "")));
      return Optional.of(
          KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm())
              .generatePrivate(keySpec));
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          e, "Expected base64-encoded PKCS#8 key in %s, but base64 decoding failed", keyPath);
    } catch (NoSuchAlgorithmException e) {
      throw new HumanReadableException(e, "Invalid algorithm specified by certificate");
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read the client key at %s", keyPath);
    } catch (InvalidKeySpecException e) {
      throw new HumanReadableException(
          e, "Client key at %s was not valid: %s", keyPath, e.getMessage());
    }
  }

  /**
   * Parses a PEM encoded X509 certificate
   *
   * @param certPathOptional The location of the certificate
   * @param required whether to throw or ignore on unset / missing / expired certificates
   * @throws {@link HumanReadableException} on issues with certificate
   */
  public static Optional<X509Certificate> parseCertificate(
      Optional<Path> certPathOptional, boolean required) {
    if (!isFileAvailable(certPathOptional, "certificate", required)) {
      return Optional.empty();
    }
    Path certPath = certPathOptional.get();
    try (InputStream certificateIn = Files.newInputStream(certPath)) {
      X509Certificate certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509").generateCertificate(certificateIn);
      certificate.checkValidity();
      return Optional.of(certificate);
    } catch (CertificateExpiredException e) {
      throwIfRequired(required, e, "The client certificate at %s has expired", certPath);
    } catch (CertificateNotYetValidException e) {
      throwIfRequired(required, e, "The client certificate at %s is not yet valid", certPath);
    } catch (CertificateException e) {
      throw new HumanReadableException(
          e,
          "The client certificate at %s does not appear to contain a valid X509 certificate",
          certPath);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read the client certificate at %s", certPath);
    }
    return Optional.empty();
  }

  private static Optional<HandshakeCertificates> parseHandshakeCertificates(
      Optional<Path> keyPath, Optional<Path> certPath, boolean required) {
    // Load the client certificate
    Optional<X509Certificate> certificate = parseCertificate(certPath, required);
    if (certificate.isPresent()) {
      Optional<PrivateKey> privateKey = parsePrivateKey(keyPath, certificate.get(), required);
      if (privateKey.isPresent()) {
        HeldCertificate cert =
            new HeldCertificate(
                new KeyPair(certificate.get().getPublicKey(), privateKey.get()), certificate.get());
        HandshakeCertificates.Builder hsBuilder = new HandshakeCertificates.Builder();
        hsBuilder.addPlatformTrustedCertificates();
        hsBuilder.heldCertificate(cert);
        return Optional.of(hsBuilder.build());
      }
    }
    return Optional.empty();
  }

  public HandshakeCertificates getHandshakeCertificates() {
    return handshakeCertificates;
  }

  public Optional<HostnameVerifier> getHostnameVerifier() {
    return hostnameVerifier;
  }
}
