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

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.google.common.base.Optional;
import com.google.common.io.Files;

import org.eclipse.jetty.server.handler.MovedContextHandler;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

public class HttpDownloaderIntegrationTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Downloader downloader;
  private Path outputDir;
  private static HttpdForTests httpd;

  @BeforeClass
  public static void startHttpd() throws Exception {
    httpd = new HttpdForTests();
    httpd.addHandler(new MovedContextHandler(null, "/redirect", "/out"));
    httpd.addStaticContent("cheese");

    httpd.start();
  }

  @AfterClass
  public static void shutdownHttpd() throws Exception {
    httpd.close();
  }

  @Before
  public void createDownloader() throws IOException {
    downloader = new HttpDownloader(Optional.<Proxy>absent(), Optional.<String>absent());
    outputDir = tmp.newFolder().toPath();
  }

  @Test
  public void canDownloadFromAUrlDirectly() throws IOException, URISyntaxException {
    URI uri = httpd.getUri("/example");

    Path output = outputDir.resolve("cheese");
    downloader.fetch(BuckEventBusFactory.newInstance(), uri, output);

    assertEquals("cheese", Files.toString(output.toFile(), UTF_16));
  }

  @Test
  public void canDownloadFromAUrlWithARedirect() throws IOException, URISyntaxException {
    URI uri = httpd.getUri("/redirect");

    Path output = outputDir.resolve("cheese");
    downloader.fetch(BuckEventBusFactory.newInstance(), uri, output);

    assertEquals("cheese", Files.toString(output.toFile(), UTF_16));
  }
}
