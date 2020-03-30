/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/** Holder and certificate parser for HTTPS client certificates. */
public class ClientCertificateHandler {

  /** Holds response of parseCertificateChain */
  public static class CertificateInfo {
    /** Create a new CertificateInfo instance */
    public CertificateInfo(X509Certificate primaryCert, ImmutableList<X509Certificate> chain) {
      this.primaryCert = primaryCert;
      this.chain = chain;
    }

    private final X509Certificate primaryCert;
    private final ImmutableList<X509Certificate> chain;

    public X509Certificate getPrimaryCert() {
      return primaryCert;
    }

    public ImmutableList<X509Certificate> getChain() {
      return chain;
    }
  };

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
    return fromConfiguration(config, Optional.empty());
  }

  /**
   * Create a new ClientCertificateHandler based on client tls settings in configuration, with
   * optional HostnameVerifier to allow for ignoring hostname mismatches in tests
   */
  public static Optional<ClientCertificateHandler> fromConfiguration(
      ArtifactCacheBuckConfig config, Optional<HostnameVerifier> hostnameVerifier) {
    Optional<Path> key = config.getClientTlsKey();
    Optional<Path> certificate = config.getClientTlsCertificate();
    Optional<Path> trustedCertificates = config.getClientTlsTrustedCertificates();
    boolean required = config.getClientTlsCertRequired();
    return parseHandshakeCertificates(key, certificate, trustedCertificates, required)
        .map(
            handshakeCertificates ->
                new ClientCertificateHandler(handshakeCertificates, hostnameVerifier));
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
   * Filter an InputStream containing PEM sections into another InputStream just containing sections
   * of a specific PEM block type
   *
   * @param inStream original input stream
   * @param label PEM block label e.g. CERTIFICATE or PRIVATE KEY
   * @return filtered {@code inStream}
   * @throws IOException
   */
  public static InputStream filterPEMInputStream(InputStream inStream, String label)
      throws IOException {
    String fileContent = new String(ByteStreams.toByteArray(inStream), Charsets.UTF_8);
    /* See https://www.rfc-editor.org/rfc/rfc7468.html#section-3 */
    Matcher matcher =
        Pattern.compile(
                "-----BEGIN " + label + "-----" + "[^-]*" + "-----END " + label + "-----",
                Pattern.DOTALL | Pattern.MULTILINE)
            .matcher(fileContent);
    List<String> pemSections = new ArrayList<>();
    while (matcher.find()) {
      pemSections.add(fileContent.substring(matcher.start(), matcher.end()));
    }
    return new ByteArrayInputStream(
        String.join(System.lineSeparator(), pemSections).getBytes(Charsets.UTF_8));
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
   * Parses a PEM encoded X509 certificate chain from a file which may contain non-certificate
   * sections after the certificate chain. The actual certificate must be placed at the beginning of
   * the file with the rest of the certificate chain following in order up to but not including the
   * trusted root.
   *
   * @param certPathOptional The location of the certificate chain file
   * @param required whether to throw or ignore on unset / missing / expired certificates
   * @throws {@link HumanReadableException} on issues with certificate
   */
  public static Optional<CertificateInfo> parseCertificateChain(
      Optional<Path> certPathOptional, boolean required) {
    if (!isFileAvailable(certPathOptional, "certificate", required)) {
      return Optional.empty();
    }
    Path certPath = certPathOptional.get();
    X509Certificate primaryCert = null;
    ImmutableList.Builder<X509Certificate> chainBuilder = new ImmutableList.Builder<>();
    int numCertsInChain = 0;
    try (InputStream certificateIn =
        filterPEMInputStream(Files.newInputStream(certPath), "CERTIFICATE")) {
      while (true) {
        X509Certificate cert =
            (X509Certificate)
                CertificateFactory.getInstance("X.509").generateCertificate(certificateIn);
        cert.checkValidity();
        if (numCertsInChain == 0) {
          primaryCert = cert;
        } else {
          chainBuilder.add(cert);
        }
        numCertsInChain++;
      }
    } catch (CertificateExpiredException e) {
      throwIfRequired(
          required, e, "Certificate #%d from the top in %s has expired", numCertsInChain, certPath);
    } catch (CertificateNotYetValidException e) {
      throwIfRequired(
          required,
          e,
          "Certificate #%d from the top on %s is not yet valid",
          numCertsInChain,
          certPath);
    } catch (CertificateException e) {
      if (numCertsInChain == 0 || primaryCert == null) {
        throw new HumanReadableException(
            e,
            "No parsable X509 certificates found in %s while looking for certificate #%d from the top: "
                + e.toString(),
            certPath,
            numCertsInChain);
      } else {
        /* EOF or first non-certificate (e.g. private key) section reached */
        return Optional.of(new CertificateInfo(primaryCert, chainBuilder.build()));
      }
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read certificate(s) file at %s", certPath);
    }
    return Optional.empty();
  }

  /**
   * Parses a file containing PEM encoded X509 certificates
   *
   * @param certPathOptional The location of the certificates file
   * @param required whether to throw or ignore on unset / missing / expired certificates
   * @throws {@link HumanReadableException} on issues with a certificate
   */
  public static ImmutableList<X509Certificate> parseCertificates(
      Optional<Path> certPathOptional, boolean required) {
    if (!isFileAvailable(certPathOptional, "certificate", required)) {
      return ImmutableList.of();
    }
    Path certPath = certPathOptional.get();
    try (InputStream certificateIn = Files.newInputStream(certPath)) {
      return CertificateFactory.getInstance("X.509").generateCertificates(certificateIn).stream()
          .map(cert -> (X509Certificate) cert)
          .filter(
              cert -> {
                try {
                  cert.checkValidity();
                  return true;
                } catch (CertificateExpiredException e) {
                  throwIfRequired(required, e, "The certificate at %s has expired", certPath);
                } catch (CertificateNotYetValidException e) {
                  throwIfRequired(required, e, "The certificate at %s is not yet valid", certPath);
                }
                return false;
              })
          .collect(ImmutableList.toImmutableList());
    } catch (CertificateException e) {
      throw new HumanReadableException(
          e,
          "Some Certificate(s) in %s do not appear to be valid X509 certificates" + e.toString(),
          certPath);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read certificate(s) file at %s", certPath);
    }
  }

  private static Optional<HandshakeCertificates> parseHandshakeCertificates(
      Optional<Path> keyPath,
      Optional<Path> certPath,
      Optional<Path> trustedCaCertificates,
      boolean required) {
    HandshakeCertificates.Builder hsBuilder = new HandshakeCertificates.Builder();
    boolean shouldReturnHandshakeCerts = false;
    hsBuilder.addPlatformTrustedCertificates();
    ImmutableList<X509Certificate> extraCaCertificates =
        parseCertificates(trustedCaCertificates, false);
    if (!extraCaCertificates.isEmpty()) {
      extraCaCertificates.stream().forEachOrdered(hsBuilder::addTrustedCertificate);
      shouldReturnHandshakeCerts = true;
    }
    // Load the client certificate chain
    Optional<CertificateInfo> certInfo = parseCertificateChain(certPath, required);
    if (certInfo.isPresent()) {
      X509Certificate clientCert = certInfo.get().getPrimaryCert();
      Optional<PrivateKey> privateKey = parsePrivateKey(keyPath, clientCert, required);
      if (privateKey.isPresent()) {
        HeldCertificate heldCert =
            new HeldCertificate(
                new KeyPair(clientCert.getPublicKey(), privateKey.get()), clientCert);
        hsBuilder.heldCertificate(
            heldCert, certInfo.get().getChain().stream().toArray(X509Certificate[]::new));
        shouldReturnHandshakeCerts = true;
      }
    }
    return shouldReturnHandshakeCerts ? Optional.of(hsBuilder.build()) : Optional.empty();
  }

  public HandshakeCertificates getHandshakeCertificates() {
    return handshakeCertificates;
  }

  public Optional<HostnameVerifier> getHostnameVerifier() {
    return hostnameVerifier;
  }
}
