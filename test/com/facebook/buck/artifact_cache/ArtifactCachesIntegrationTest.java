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
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheStoreResponse;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.TaskManagerScope;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.google.common.base.Charsets;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.X509KeyManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ArtifactCachesIntegrationTest {

  /**
   * Certs generated with: openssl genrsa -out ca.key 2048 openssl req -new -x509 -days 3650 -key
   * ca.key -out ca.crt openssl genrsa -out client.key 2048 openssl req -new -key client.key -out
   * client.csr openssl x509 -req -days 3650 -in client.csr -CA ca.crt -CAkey ca.key -set_serial 01
   * -out client.crt openssl pkcs8 -inform PEM -outform PEM -in client.key -out client.key.pkcs8
   * -nocrypt -topk8 openssl genrsa -out server.key 2048 openssl req -new -key server.key -out
   * server.csr openssl x509 -req -days 3650 -in server.csr -CA ca.crt -CAkey ca.key -set_serial 02
   * -out server.crt openssl pkcs8 -inform PEM -outform PEM -in server.key -out server.key.pkcs8
   * -nocrypt -topk8
   */
  private static final String SAMPLE_CLIENT_CERT =
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

  private static final String SAMPLE_CLIENT_KEY =
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

  private static final String SAMPLE_SERVER_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDEjCCAfoCAQIwDQYJKoZIhvcNAQEFBQAwTTELMAkGA1UEBhMCVVMxEzARBgNV\n"
          + "BAgMCldhc2hpbmd0b24xEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAMMDmNhLmV4\n"
          + "YW1wbGUuY29tMB4XDTE4MTAwMzE4MzAwOVoXDTI4MDkzMDE4MzAwOVowUTELMAkG\n"
          + "A1UEBhMCVVMxEzARBgNVBAgMCldhc2hpbmd0b24xEDAOBgNVBAcMB1NlYXR0bGUx\n"
          + "GzAZBgNVBAMMEnNlcnZlci5leGFtcGxlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQAD\n"
          + "ggEPADCCAQoCggEBAMRkUtz2qYmvJ2xWbVNHbGOoEinDqxXY749kEVf9swGDCtbd\n"
          + "qNmuB3P81CoEWm3O1ZnIBfgD6hNzC9YC0KJ16x7B7YbR37rw/so0AVIdiDU2Ftjz\n"
          + "N76Ih+GXKV1ZXE8Noq5W9BfccMxhwEr5+eI1v11V91x9LgMLzbUzbnr0SS7+/VOb\n"
          + "0C2tG2QSvyY33NMAAGyFRc9EPIB22blaIaylTqsp11akDd8im1+x/lpvJAt7qgxX\n"
          + "8bgtlI+z7d7bmvdO6bUlFWYXxgl2SFNdbFHyz7TvbbQrxEfTMZiFYgsmrp+0mGG7\n"
          + "2putPVyvE2x9sMUCXsnsZT6gHGKaNGURFWiJwtECAwEAATANBgkqhkiG9w0BAQUF\n"
          + "AAOCAQEAOlzAIo2c36+VUtZNbrOs767daO0WY4a+5tV+z9wU5dNa09MO74yN0cYl\n"
          + "O+4Kf9646GvVFfP0d3YLSivWJ8BC2j6m/plugnyjorO5eGdTeWaZk8foRpnK/yys\n"
          + "lCU7OT8NxmUp+ch+Oer6RyOG18HP7eRV5ejC+PoaCFlAq+rrdA9dZm4sQCRgWVd9\n"
          + "xWXJVSTmF2X7U6bT4r52P7ETLpqiG7ZHkZvZo8KbPi8U5V0CAqsV9J9QDOfJvstl\n"
          + "oN2PC/nv6B6b7ZNGj3wMWhoYBDT7gWgKeM0PlXERyjgMX3Ckn4j67u4trV1/TLUz\n"
          + "MUcHV2A4aEJMpR+W74/BRmKAPMwUCw==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_SERVER_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDEZFLc9qmJryds\n"
          + "Vm1TR2xjqBIpw6sV2O+PZBFX/bMBgwrW3ajZrgdz/NQqBFptztWZyAX4A+oTcwvW\n"
          + "AtCidesewe2G0d+68P7KNAFSHYg1NhbY8ze+iIfhlyldWVxPDaKuVvQX3HDMYcBK\n"
          + "+fniNb9dVfdcfS4DC821M2569Eku/v1Tm9AtrRtkEr8mN9zTAABshUXPRDyAdtm5\n"
          + "WiGspU6rKddWpA3fIptfsf5abyQLe6oMV/G4LZSPs+3e25r3Tum1JRVmF8YJdkhT\n"
          + "XWxR8s+07220K8RH0zGYhWILJq6ftJhhu9qbrT1crxNsfbDFAl7J7GU+oBximjRl\n"
          + "ERVoicLRAgMBAAECggEAK5PPRzR8Xg69gq6Sx53bkSlkDlxahBiE355mss2agMVh\n"
          + "DFhW9SZGhRgew8v/fMoeX2cg2+2SbQpkH/Kz9LiRmVuSpw2+xS5geuGbQWtII/aC\n"
          + "j1U4k1CcRhRSm2IOt4PhCypEM184sEEod/qL1gPzGHTQ1Hb6VLazyHdHFoVKD+Ek\n"
          + "aATfYPYM8NEyPebkJVxjWGHv+eZXwyrF2mGiOoBOLlUWl+VkIHkTB7qe+nKsrtyw\n"
          + "JhJKt2Z2+390EL4Rxg0uqp5thvd4scKAzB58m42zd0m09X89Lw6722PmYlMCf9qX\n"
          + "5dNLxVkTQyiwn55JHN0Zlo+pyrDEijmRG+wDXqJgIQKBgQD+sT8Hs7vFaM3l7gt0\n"
          + "fsZtTOOMU4/ksEtPlQNQaTeoiSmMr6xGak4Xvr6LD/SxB1dPvaT0QSTJEQkXUx4U\n"
          + "G8zuNtJut4+dO3XV/88l+MDYSsI5KbwH5bYWwPXnsCTNf46IBMArbpoCJeVYV+W3\n"
          + "SdHDcG6QhvSsGXmzvEIWOyeOBQKBgQDFZnNTunRZEIhKAffFK0SnJL/kTKkVERW6\n"
          + "SWMqMoTW2ZckpSMnyfFbyz4LX0rl6MLOzGuk4ttVjCT7yvjhBiDUc2yY2wSFc5DK\n"
          + "gyuJqoVkcAklxGvQ1Yc07eMIB64Ipjz0J2kDaxjsn/TLYGGZlq8RO67nuUeH5Jrv\n"
          + "C++BrutvXQKBgQD8XFHw5sVSSJNjlafiCU/Bs2Lwg0fbuFcXBrae8XKF20rBLLwN\n"
          + "lX3Fh2mzzt6MnpKD34xXvUietfOFGgV+tUEsdEO0EswJZoZOwcbWgBFM/15NV64J\n"
          + "QTJYf1/o7x64RADNg6+KGXAeWsBR9d4W690dwwS6zg4XjLKLRilRb9G0pQKBgAtC\n"
          + "Tq2l4uD5mmxuNE2grCfEZtWEsdgrw0t+yBMuEnmWq5JBgQHR+Nw9eWp4ovL+Fa5p\n"
          + "5nHfJpd4iNt7tjpPeSvk8Xq+c0GRV97VIHSXr0gNQ9hNncCpjS6tqtdYaMrBgJSE\n"
          + "cu7o+uD0Nqgq9SYnfBDFkLJS1QuhNF0SFzUUXwVZAoGAfhGMXxl1uDkFIoSOySEV\n"
          + "d7DPQJLFT9crsqBbpA7nDuNB/3BXUqCjz+MaVHpNQEykfke9rAuswNjRl75cTcnE\n"
          + "m2NkREF3dEi1CllNZxb0LRFVpAmJwBFFcevpqvQnDSokZ7/5tUsaUMrR+mbq5vaV\n"
          + "9lVGHblMKOFQ22R+E4yI7G8=\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_CA_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDFjCCAf4CCQCFJYJEzO/NoTANBgkqhkiG9w0BAQsFADBNMQswCQYDVQQGEwJV\n"
          + "UzETMBEGA1UECAwKV2FzaGluZ3RvbjEQMA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UE\n"
          + "AwwOY2EuZXhhbXBsZS5jb20wHhcNMTgxMDAzMTgyOTM3WhcNMjgwOTMwMTgyOTM3\n"
          + "WjBNMQswCQYDVQQGEwJVUzETMBEGA1UECAwKV2FzaGluZ3RvbjEQMA4GA1UEBwwH\n"
          + "U2VhdHRsZTEXMBUGA1UEAwwOY2EuZXhhbXBsZS5jb20wggEiMA0GCSqGSIb3DQEB\n"
          + "AQUAA4IBDwAwggEKAoIBAQDO+b9sMlJPpLbS0YHRz5jNUikshqelUriLLGyPKIcH\n"
          + "4Gd2HXp1kIpkN9U+h30EKAO2kluSkzU6q1EPOg54VIdZwOI+89F3fGvTuACGBBRW\n"
          + "KXDnLLfBj/XKE4JRAHxej8rrJzbYNv3iy1jcSRdMPdXw+RMBt4fbS7JuEeY1c1iM\n"
          + "hFbCZAqBgOWRQV+JqZJ5hv0x2Y8hmTQN8O8PRl5VwpKT+aNrpunyQVYdyzfMtHrm\n"
          + "c4W3MU2W9Oa0mTLzVAa9rVrpOxpSmEfXcMY8RIIN1mOK0yuJVPW8K0TcKy/HfO14\n"
          + "4OBsbzrlvtVNLSMeiCIvsROXJCBd90ZtE3DHwZYRvltTAgMBAAEwDQYJKoZIhvcN\n"
          + "AQELBQADggEBADEhwOYsugkZ0IhTD9CGPnCqOY97iLaVXy7/P7xnOtzlXJiK/AMj\n"
          + "tapwh9mG1vgpOv9orCyTm6GZqBSObhoymyFGoxWgku3nQyiqJDQFAbB2+N46H5GC\n"
          + "r4Cu+MnPGljJtNClVn+Q9CRKuaOSiRYygGc84bUbQAeMuPnRswK2IInAahzfpWWI\n"
          + "xYFwXb3611NqPQAIwnFdgpmsm4Ko82xh5sWhchRy5BwIlGUXxrFAOUOMonvIUzSW\n"
          + "hitWCW5AMwHKOeTs0/4BmJE/6rmR84ozZ0z3X/5+LAYLeI+GbUZBr/kUB3euXaj4\n"
          + "rGN2EuvbdWav5As8evyWUnB5QGxTTeptYCg=\n"
          + "-----END CERTIFICATE-----";

  private static final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
  private static final Path outputPath = Paths.get("output/file");
  private static final BuildId BUILD_ID = new BuildId("test");

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  private BackgroundTaskManager bgTaskManager;
  private TaskManagerScope managerScope;
  private Path clientCertPath;
  private Path clientKeyPath;
  private Path serverCertPath;
  private Path serverKeyPath;
  private Path caCertPath;
  private ClientCertificateHandler clientCertificateHandler;

  @Before
  public void setUp() throws IOException {
    bgTaskManager = new TestBackgroundTaskManager();
    managerScope = bgTaskManager.getNewScope(BUILD_ID);

    clientCertPath = tempDir.newFile("client.crt");
    clientKeyPath = tempDir.newFile("client.key");
    serverCertPath = tempDir.newFile("server.crt");
    serverKeyPath = tempDir.newFile("server.key");
    caCertPath = tempDir.newFile("ca.crt");

    Files.write(clientCertPath, SAMPLE_CLIENT_CERT.getBytes(Charsets.UTF_8));
    Files.write(clientKeyPath, SAMPLE_CLIENT_KEY.getBytes(Charsets.UTF_8));
    Files.write(serverCertPath, SAMPLE_SERVER_CERT.getBytes(Charsets.UTF_8));
    Files.write(serverKeyPath, SAMPLE_SERVER_KEY.getBytes(Charsets.UTF_8));
    Files.write(caCertPath, SAMPLE_CA_CERT.getBytes(Charsets.UTF_8));
    clientCertificateHandler =
        createClientCertificateHandler(clientKeyPath, clientCertPath, caCertPath);
  }

  @After
  public void tearDown() throws InterruptedException {
    managerScope.close();
    bgTaskManager.shutdown(1, TimeUnit.SECONDS);
  }

  @Test
  public void testUsesClientTlsCertsForHttpsFetch() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, false);

    X509KeyManager keyManager = clientCertificateHandler.getHandshakeCertificates().keyManager();
    X509Certificate clientCert =
        keyManager.getCertificateChain(keyManager.getClientAliases("RSA", null)[0])[0];

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig)
              .remoteOnlyInstance(false, false)) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(result.cacheError().orElse(""), CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftFetch() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, false);

    X509KeyManager keyManager = clientCertificateHandler.getHandshakeCertificates().keyManager();
    X509Certificate clientCert =
        keyManager.getCertificateChain(keyManager.getClientAliases("RSA", null)[0])[0];

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig)
              .remoteOnlyInstance(false, false)) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForHttpsStore() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, true);

    X509KeyManager keyManager = clientCertificateHandler.getHandshakeCertificates().keyManager();
    X509Certificate clientCert =
        keyManager.getCertificateChain(keyManager.getClientAliases("RSA", null)[0])[0];

    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig)
              .remoteOnlyInstance(false, false)) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftStore() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, true);

    X509KeyManager keyManager = clientCertificateHandler.getHandshakeCertificates().keyManager();
    X509Certificate clientCert =
        keyManager.getCertificateChain(keyManager.getClientAliases("RSA", null)[0])[0];

    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig)
              .remoteOnlyInstance(false, false)) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  private ArtifactCaches newArtifactCache(
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ArtifactCacheBuckConfig cacheConfig) {
    return new ArtifactCaches(
        cacheConfig,
        buckEventBus,
        projectFilesystem,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        managerScope,
        "test://",
        "myhostname",
        Optional.of(clientCertificateHandler));
  }

  /**
   * Create a ClientCertificateHandler that accepts all hostnames (so that we don't have to setup
   * hostnames for tests), and that accepts certs signed by the CA
   */
  private ClientCertificateHandler createClientCertificateHandler(
      Path clientKeyPath, Path clientCertPath, Path caCertPath) throws IOException {
    X509Certificate certificate = ClientCertificateHandler.parseCertificate(clientCertPath);
    X509Certificate caCertificate = ClientCertificateHandler.parseCertificate(caCertPath);
    PrivateKey privateKey = ClientCertificateHandler.parsePrivateKey(clientKeyPath, certificate);

    HeldCertificate cert =
        new HeldCertificate(new KeyPair(certificate.getPublicKey(), privateKey), certificate);
    HandshakeCertificates.Builder hsBuilder = new HandshakeCertificates.Builder();
    hsBuilder.addPlatformTrustedCertificates();
    hsBuilder.addTrustedCertificate(caCertificate);
    hsBuilder.heldCertificate(cert);
    return new ClientCertificateHandler(hsBuilder.build(), Optional.of((s, sslSession) -> true));
  }

  class NotFoundHandler extends AbstractHandler {

    private final boolean isThrift;
    private final boolean isStore;
    List<X509Certificate[]> peerCertificates = new ArrayList<>();

    public NotFoundHandler(boolean isThrift, boolean isStore) {
      this.isThrift = isThrift;
      this.isStore = isStore;
    }

    @Override
    public void handle(
        String s,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse)
        throws IOException {

      X509Certificate[] certs =
          (X509Certificate[])
              httpServletRequest.getAttribute("javax.servlet.request.X509Certificate");
      peerCertificates.add(certs);
      if (isThrift) {
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);

        BuckCacheResponse response = new BuckCacheResponse();
        response.setWasSuccessful(true);

        if (isStore) {
          BuckCacheStoreResponse storeResponse = new BuckCacheStoreResponse();
          response.setStoreResponse(storeResponse);
        } else {
          BuckCacheFetchResponse fetchResponse = new BuckCacheFetchResponse();
          fetchResponse.setArtifactExists(false);
          response.setFetchResponse(fetchResponse);
        }

        byte[] serialized = ThriftUtil.serialize(ThriftArtifactCache.PROTOCOL, response);
        httpServletResponse.setContentType("application/octet-stream");
        httpServletResponse
            .getOutputStream()
            .write(ByteBuffer.allocate(4).putInt(serialized.length).array());
        httpServletResponse.getOutputStream().write(serialized);
        httpServletResponse.getOutputStream().close();
      } else {
        if (isStore) {
          httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        } else {
          httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
      }
      request.setHandled(true);
    }
  }
}
