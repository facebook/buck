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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  public static Optional<ClientCertificateHandler> fromConfiguration(ArtifactCacheBuckConfig config)
      throws IOException {
    if (!config.getClientTlsKey().isPresent() || !config.getClientTlsCertificate().isPresent()) {
      return Optional.empty();
    }

    HandshakeCertificates handshakeCertificates =
        parseHandshakeCertificates(
            config.getClientTlsKey().get(), config.getClientTlsCertificate().get());
    return Optional.of(new ClientCertificateHandler(handshakeCertificates, Optional.empty()));
  }

  /**
   * Parse a PEM encoded private key, with the algorithm decided by {@code certificate}
   *
   * @param keyPath The path to a PEM encoded PKCS#8 private key
   * @param certificate The corresponding public key. Used to determine key's algorithm
   * @throws HumanReadableException if the key file could not be parsed
   * @throws IOException if the key file could not be read
   */
  public static PrivateKey parsePrivateKey(Path keyPath, X509Certificate certificate)
      throws IOException {
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
      return KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm())
          .generatePrivate(keySpec);
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          e, "Expected base64-encoded PKCS#8 key in %s, but base64 decoding failed", keyPath);
    } catch (NoSuchAlgorithmException e) {
      throw new HumanReadableException(e, "Invalid algorithm specified by certificate");
    } catch (IOException e) {
      throw new IOException(String.format("Could not read the client key at %s", keyPath), e);
    } catch (InvalidKeySpecException e) {
      throw new HumanReadableException(
          e, "Client key at %s was not valid: %s", keyPath, e.getMessage());
    }
  }

  /**
   * Parses a PEM encoded X509 certificate
   *
   * @param certPath The location of the certificate
   * @throws {@link HumanReadableException} if the certificate could not be parsed
   */
  public static X509Certificate parseCertificate(Path certPath) {
    try (InputStream certificateIn = Files.newInputStream(certPath)) {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509").generateCertificate(certificateIn);
    } catch (CertificateException e) {
      throw new HumanReadableException(
          e,
          "The client certificate at %s does not appear to contain a valid X509 certificate",
          certPath);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read the client certificate at %s", certPath);
    }
  }

  private static HandshakeCertificates parseHandshakeCertificates(Path keyPath, Path certPath)
      throws IOException {
    // Load the client certificate
    X509Certificate certificate = parseCertificate(certPath);
    PrivateKey privateKey = parsePrivateKey(keyPath, certificate);

    HeldCertificate cert =
        new HeldCertificate(new KeyPair(certificate.getPublicKey(), privateKey), certificate);
    HandshakeCertificates.Builder hsBuilder = new HandshakeCertificates.Builder();
    hsBuilder.addPlatformTrustedCertificates();
    hsBuilder.heldCertificate(cert);
    return hsBuilder.build();
  }

  public HandshakeCertificates getHandshakeCertificates() {
    return handshakeCertificates;
  }

  public Optional<HostnameVerifier> getHostnameVerifier() {
    return hostnameVerifier;
  }
}
