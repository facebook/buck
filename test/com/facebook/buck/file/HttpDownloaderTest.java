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

package com.facebook.buck.file;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HttpDownloaderTest {

  private final Path neverUsed = Paths.get("never/used");
  private BuckEventBus eventBus = BuckEventBusFactory.newInstance();

  @Test
  public void shouldThrowAnExceptionIfTheStatusCodeIsNot200()
      throws IOException, URISyntaxException {
    final HttpURLConnection connection = EasyMock.createNiceMock(HttpURLConnection.class);
    EasyMock.expect(connection.getResponseCode()).andStubReturn(HTTP_FORBIDDEN);

    EasyMock.replay(connection);

    Downloader downloader =
        new HttpDownloader(Optional.<Proxy>absent()) {
          @Override
          protected HttpURLConnection createConnection(URI uri) throws IOException {
            return connection;
          }
        };
    try {
      downloader.fetch(eventBus, new URI("http://example.com"), neverUsed);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("Unable to download"));
    }

    EasyMock.verify(connection);
  }

  @Test
  public void shouldReturnFalseIfUrlIsNotHttp() throws URISyntaxException, IOException {
    Downloader downloader = new HttpDownloader(Optional.<Proxy>absent());

    boolean result = downloader.fetch(eventBus, new URI("mvn:foo/bar/baz"), neverUsed);

    assertFalse(result);
  }
}
