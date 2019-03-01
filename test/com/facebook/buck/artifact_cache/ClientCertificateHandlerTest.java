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
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.net.ssl.X509KeyManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ClientCertificateHandlerTest {

  private String sampleClientCert =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDEjCCAfoCAQEwDQYJKoZIhvcNAQEFBQAwTTELMAkGA1UEBhMCVVMxEzARBgNV\n"
          + "BAgMCldhc2hpbmd0b24xEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAMMDmNhLmV4\n"
          + "YW1wbGUuY29tMB4XDTE4MTAwMzE4Mjk1N1oXDTI4MDkzMDE4Mjk1N1owUTELMAkG\n"
          + "A1UEBhMCVVMxEzARBgNVBAgMCldhc2hpbmd0b24xEDAOBgNVBAcMB1NlYXR0bGUx\n"
          + "GzAZBgNVBAMMEmNsaWVudC5leGFtcGxlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQAD\n"
          + "ggEPADCCAQoCggEBANyyXUD+FSzICbv2JF7Z0Xnx9HVO1sZFjJlmTDknt/nyRw1y\n"
          + "sOZMO1LE7Wit24k6amqAZhjceehmPZIdQbtLxpBzwmtYum6qymLaC34Xx2LYEG4P\n"
          + "RJHY9AtMrb8hd4X4ZQD+bhAH59u+kTnVO+0vlOnyT3xPKVQQ+FEoErfmBDpKSiaj\n"
          + "v/ireTC/VrAcB24qhONKJuWK8xxu7vuJ6uNFU83IXrqgjS0iXkDWTXAI/dxZLexZ\n"
          + "Gm6nu66VCZlUbAP2Q1L0SPf2ZGKORo8VBl6MI+cT09k31CFjU4xaW9vosQQ9dInb\n"
          + "/5FK9K65OrbRIO3QdZclMs1Y5uDpLiFSvDfrZmMCAwEAATANBgkqhkiG9w0BAQUF\n"
          + "AAOCAQEAY56F8WLWwoCDCYF1YD4LcMRq8cuCMTTNk9v8gD7VSORuC+7bsyvTpMLx\n"
          + "qZbNNCJsv+L5GbRDnH4T98OEtrOn6pAgW8y7jKZNISPo6Tqohvn2Bi40OiCBNynr\n"
          + "0bki3HpLxDqgkOjbNCO35vHLs7ZtY1EijBnphlW57e4rXMAe63qkblWSXfxKo33+\n"
          + "0l4OiL/O0gPRrdeJEAU0k/GPgMdHd3QierkKg9LhZEIuU3bTMassPiWDwVGUXDov\n"
          + "ZEWyZ0qCcFV2nj23zPr/16FFvxuZEcGd9fBDLbJHbkBH1nlikFnchjvGCzH5gEcA\n"
          + "hpHC7IcvLgJPOTV0HbyaHmCxhBU8IQ==\n"
          + "-----END CERTIFICATE-----";

  private String sampleClientKey =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEugIBADANBgkqhkiG9w0BAQEFAASCBKQwggSgAgEAAoIBAQDcsl1A/hUsyAm7\n"
          + "9iRe2dF58fR1TtbGRYyZZkw5J7f58kcNcrDmTDtSxO1orduJOmpqgGYY3HnoZj2S\n"
          + "HUG7S8aQc8JrWLpuqspi2gt+F8di2BBuD0SR2PQLTK2/IXeF+GUA/m4QB+fbvpE5\n"
          + "1TvtL5Tp8k98TylUEPhRKBK35gQ6Skomo7/4q3kwv1awHAduKoTjSiblivMcbu77\n"
          + "ierjRVPNyF66oI0tIl5A1k1wCP3cWS3sWRpup7uulQmZVGwD9kNS9Ej39mRijkaP\n"
          + "FQZejCPnE9PZN9QhY1OMWlvb6LEEPXSJ2/+RSvSuuTq20SDt0HWXJTLNWObg6S4h\n"
          + "Urw362ZjAgMBAAECggEAJjDjfFS7u1Uegh1VK+jLnCunnwk2l3b/nqgaNqXN633m\n"
          + "l8gqHqBAf9E+OCgl3nhyY922TUUR/4p5yygu8MdrJCI0GblwAaiifzq2VGqvAUbc\n"
          + "iP8xYX/Gs5HgWzviYBec+vAMgc+satVucjCZPzFFtrpM0Pkt8LNDFWA25QXz41YX\n"
          + "cpqUCR4tCGz5K3hI2XTeQehNrbCjzq01AT2+jY05JSDaU2lLc7b+tCcI+M6rWjF1\n"
          + "V2XufeVvYm/sG6eLasWIxpDKHFZCOvB1m6h1t+59d7e4y4rokjg2fspgTAjYNObb\n"
          + "YhDzxWhNUVQcbeo8OTwYZnZaQprOqY6uEo4C6E75qQKBgQD1NXB8roZoExYYkTeb\n"
          + "nK+1UD23opJTcbVGiqhP9qp7HP5o+e9Gk3TSZplZnIpaPqSib+tR3TU5lKEMefWS\n"
          + "p3Ou8K8Qgd9nlqK/gBuoT5ZmgaWr6HaUP5Pt4FOjrZ3g1M2kR7r9yqqg/moVvpiP\n"
          + "SBFzB+o6eCOjfjErTFXEXDeXvwKBgQDmaMTGvSY29QeVju8on9kgjHeZC1geYmav\n"
          + "23n6MrLwS0F5O/jVzZAll/hCljeZML/0aI733MZiDPd5qsn6jx6oyZ6GyWAtxoJw\n"
          + "JO+hRZq3dG/h74rp8aN/yfA2VPTfXCzJtsGBH9sF18eTnyfW2HXb+D7QT9tTlbQR\n"
          + "s8gL9qg6XQJ/XOjitltqkgSpWqWrbErySMEeoXX3+6YaCaCAJcxQzFUwEJajExrM\n"
          + "KOy3Lj0iLw+NUf8WKu6mPCsU2qVbZzYLnz2TF64d+CIbiHQCBsQhOLXnEDwEsidk\n"
          + "5b0Z8+rU51u6j4SeVYt1G4tKpvKQ27ly4yMcnQrodgpalw1VchF+/wKBgAa3CvUX\n"
          + "0iNL5Nqw/btbXUKblWi6cekAySla5iUqkRh7uP7Fhq0Efqz5ztxx8FDgoNeIrJIA\n"
          + "ty9oXVYIajaJMUWOCra267ypymdmTC2RD79E/3XAO3Yx+qfgxMVwmGpiD1QZpW4T\n"
          + "9Zgn/8MHomuah2TPyVTc3vGCrWrOqIfgumppAoGAB4zQWSn4+le24DHwgYwCChkT\n"
          + "s7wao8DiTMBRGYmfeAgMx/U2U3m/gN9q/+7WT+X4AKseXoWKuDegQq4CHlgkDyh2\n"
          + "B7sBo4ZpLm3kOlUlznssMBUEG2i/iyZGPwHBKcUskemLL5M2wIHH1O/CYJ3jDMcK\n"
          + "9kGk/IHTW2kCBLs+mVA=\n"
          + "-----END PRIVATE KEY-----";

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();
  @Rule public ExpectedException expected = ExpectedException.none();

  ArtifactCacheBuckConfig config_required;
  ArtifactCacheBuckConfig config_optional;
  private Path clientCertPath;
  private Path clientKeyPath;

  @Before
  public void setUp() throws IOException {
    clientCertPath = temporaryPaths.newFile("client.crt");
    clientKeyPath = temporaryPaths.newFile("client.key");
    Files.write(clientCertPath, sampleClientCert.getBytes(Charsets.UTF_8));
    Files.write(clientKeyPath, sampleClientKey.getBytes(Charsets.UTF_8));
    config_required =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_key = " + clientKeyPath.toString(),
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = yes");
    config_optional =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_key = " + clientKeyPath.toString(),
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = no");
  }

  @Test
  public void throwsIfCertIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]", "http_client_tls_cert_required = yes");
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Required option for certificate unset");

    ClientCertificateHandler.fromConfiguration(config);
  }

  @Test
  public void ignoreIfCertIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "http_client_tls_cert_required = no");
    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config).isPresent());
  }

  @Test
  public void throwsIfCertIsMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Cannot read certificate file %s", clientCertPath));

    Files.delete(clientCertPath);

    ClientCertificateHandler.fromConfiguration(config_required);
  }

  @Test
  public void ignoreIfCertIsMissing() throws IOException {
    Files.delete(clientCertPath);

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config_optional).isPresent());
  }

  @Test
  public void throwsIfX509CertIsInvalid() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("does not appear to contain a valid X509 certificate");

    Files.write(clientCertPath, "INVALID CERT".getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfKeyIsMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Cannot read private key file %s", clientKeyPath));

    Files.delete(clientKeyPath);

    ClientCertificateHandler.fromConfiguration(config_required);
  }

  @Test
  public void ignoreIfKeyIsMissing() throws IOException {
    Files.delete(clientKeyPath);

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config_optional).isPresent());
  }

  @Test
  public void throwsIfKeyIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = yes");

    expected.expect(HumanReadableException.class);
    expected.expectMessage("Required option for private key unset");

    ClientCertificateHandler.fromConfiguration(config);
  }

  @Test
  public void ignoreIfKeyIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = no");

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config).isPresent());
  }

  @Test
  public void throwsIfHeaderOrTrailerAreMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Expected BEGIN PRIVATE KEY/END PRIVATE KEY ");

    Files.write(clientKeyPath, "TESTING".getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfBase64DecodingKeyFailsAndRequired() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Expected base64-encoded PKCS#8 key");

    Files.write(
        clientKeyPath,
        "-----BEGIN PRIVATE KEY-----\nNOT BASE64\n-----END PRIVATE KEY-----\n"
            .getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfKeyIsInvalid() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Client key at %s was not valid", clientKeyPath));

    Files.write(
        clientKeyPath,
        ("-----BEGIN PRIVATE KEY-----\n"
                + "MIIJKQIBAAKCAgEAtqFSEuAK1CbaBiKfybnYMSQp1tXmgi+WdrxRT5B9MnPxe9oo\n"
                + "-----END PRIVATE KEY-----\n")
            .getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void handlesCombinedKeyAndCert() throws IOException {
    Files.write(
        clientKeyPath, (sampleClientCert + "\n" + sampleClientKey).getBytes(Charsets.UTF_8));

    String[] keyLines = sampleClientKey.split("\n");
    byte[] expectedPrivateKey =
        Base64.getDecoder()
            .decode(String.join("", Arrays.copyOfRange(keyLines, 1, keyLines.length - 1)));
    String expectedPublic = "CN=client.example.com, L=Seattle, ST=Washington, C=US";

    Optional<ClientCertificateHandler> handler =
        ClientCertificateHandler.fromConfiguration(config_required);

    X509KeyManager keyManager = handler.get().getHandshakeCertificates().keyManager();
    String alias = keyManager.getClientAliases("RSA", null)[0];
    PrivateKey privateKey = keyManager.getPrivateKey(alias);
    String subjectName = keyManager.getCertificateChain(alias)[0].getSubjectDN().getName();

    Assert.assertArrayEquals(expectedPrivateKey, privateKey.getEncoded());
    Assert.assertEquals(expectedPublic, subjectName);
    Assert.assertFalse(handler.get().getHostnameVerifier().isPresent());
  }

  @Test
  public void handlesEmptyConfig() throws IOException {
    Optional<ClientCertificateHandler> handler1 =
        ClientCertificateHandler.fromConfiguration(ArtifactCacheBuckConfigTest.createFromText());
    Optional<ClientCertificateHandler> handler2 =
        ClientCertificateHandler.fromConfiguration(
            ArtifactCacheBuckConfigTest.createFromText(
                "[cache]", "http_client_tls_key = " + clientKeyPath.toString()));
    Optional<ClientCertificateHandler> handler3 =
        ClientCertificateHandler.fromConfiguration(
            ArtifactCacheBuckConfigTest.createFromText(
                "[cache]", "http_client_tls_cert = " + clientCertPath.toString()));

    Assert.assertFalse(handler1.isPresent());
    Assert.assertFalse(handler2.isPresent());
    Assert.assertFalse(handler3.isPresent());
  }
}
