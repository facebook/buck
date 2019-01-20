/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.file.downloader.impl;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.file.downloader.Downloader;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Test;

public class HttpDownloaderTest {

  private final Path neverUsed = Paths.get("never/used");
  private BuckEventBus eventBus = BuckEventBusForTests.newInstance();

  private HttpDownloader getDownloader(HttpURLConnection connection) {
    return new HttpDownloader() {
      @Override
      protected HttpURLConnection createConnection(URI uri) {
        return connection;
      }
    };
  }

  @Test
  public void shouldReturnFalseIfTryingBasicAuthOverHttp() throws IOException, URISyntaxException {
    HttpURLConnection connection = EasyMock.createNiceMock(HttpURLConnection.class);
    EasyMock.replay(connection);

    HttpDownloader downloader = getDownloader(connection);
    PasswordAuthentication credentials = new PasswordAuthentication("not", "used".toCharArray());
    boolean result =
        downloader.fetch(
            eventBus, new URI("http://example.com"), Optional.of(credentials), neverUsed);
    assertFalse(result);

    EasyMock.verify(connection);
  }

  @Test
  public void shouldAddAuthenticationHeader() throws IOException, URISyntaxException {

    Capture<String> capturedAuth = EasyMock.newCapture();

    HttpURLConnection connection = EasyMock.createNiceMock(HttpsURLConnection.class);
    EasyMock.expect(connection.getResponseCode()).andStubReturn(HTTP_FORBIDDEN);
    connection.addRequestProperty(eq("Authorization"), capture(capturedAuth));
    EasyMock.expectLastCall();
    EasyMock.replay(connection);

    HttpDownloader downloader = getDownloader(connection);
    PasswordAuthentication credentials = new PasswordAuthentication("foo", "bar".toCharArray());
    boolean result =
        downloader.fetch(
            eventBus, new URI("https://example.com"), Optional.of(credentials), neverUsed);
    assertFalse(result);

    EasyMock.verify(connection);

    Matcher m = Pattern.compile("^Basic (.*)$").matcher(capturedAuth.getValue());
    assertTrue(m.matches());
    assertEquals(
        "foo:bar", new String(BaseEncoding.base64().decode(m.group(1)), StandardCharsets.UTF_8));
  }

  @Test
  public void shouldReturnFalseIfTheStatusCodeIsNot200() throws IOException, URISyntaxException {
    HttpURLConnection connection = EasyMock.createNiceMock(HttpURLConnection.class);
    EasyMock.expect(connection.getResponseCode()).andStubReturn(HTTP_FORBIDDEN);
    EasyMock.replay(connection);

    Downloader downloader = getDownloader(connection);
    boolean result = downloader.fetch(eventBus, new URI("http://example.com"), neverUsed);
    assertFalse(result);

    EasyMock.verify(connection);
  }

  @Test
  public void shouldReturnFalseIfUrlIsNotHttp() throws URISyntaxException, IOException {
    Downloader downloader = new HttpDownloader();

    boolean result = downloader.fetch(eventBus, new URI("mvn:foo/bar/baz"), neverUsed);

    assertFalse(result);
  }
}
