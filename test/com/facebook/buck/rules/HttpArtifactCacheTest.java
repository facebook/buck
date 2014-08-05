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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.util.ProjectFilesystem;
import static com.google.common.io.ByteStreams.nullOutputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheTest {

  private HttpArtifactCache cache;
  private HttpURLConnection connection;
  private ProjectFilesystem projectFilesystem;
  private BuckEventBus buckEventBus;

  @Before
  public void setUp() {
    connection = createNiceMock(HttpURLConnection.class);
    projectFilesystem = createMock(ProjectFilesystem.class);
    buckEventBus = BuckEventBusFactory.newInstance();
    cache = new FakeHttpArtifactCache(connection, projectFilesystem, buckEventBus);
  }

  @Test
  public void testFetchNotFound() throws IOException {
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_NOT_FOUND);
    replay(connection);
    assertEquals(cache.fetch(new RuleKey("00000000000000000000000000000000"),
          File.createTempFile("000", "")),
        CacheResult.MISS);
    verify(connection);
  }

  @Test
  public void testFetchOK() throws IOException {
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_OK);
    InputStream is = new ByteArrayInputStream("test".getBytes());
    expect(connection.getInputStream()).andReturn(is);
    File file = File.createTempFile("000", "");
    Path path = file.toPath();
    projectFilesystem.createParentDirs(path);
    projectFilesystem.copyToPath(is, path, StandardCopyOption.REPLACE_EXISTING);
    replay(connection);
    replay(projectFilesystem);
    assertEquals(cache.fetch(new RuleKey("00000000000000000000000000000000"), file),
        CacheResult.HTTP_HIT);
    verify(connection);
    verify(projectFilesystem);
  }

  @Test
  public void testStore() throws IOException {
    connection.setConnectTimeout(1000);
    connection.setDoOutput(true);
    connection.setRequestMethod("POST");
    expect(connection.getOutputStream()).andReturn(nullOutputStream());
    File file = File.createTempFile("000", "");
    InputStream is = new ByteArrayInputStream("test".getBytes());
    expect(projectFilesystem.newFileInputStream(file.toPath())).andReturn(is);
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_ACCEPTED);
    replay(connection);
    replay(projectFilesystem);
    cache.store(new RuleKey("00000000000000000000000000000000"), file);
    verify(connection);
    verify(projectFilesystem);
  }

  class FakeHttpArtifactCache extends HttpArtifactCache {
    private HttpURLConnection connectionMock;

    FakeHttpArtifactCache(HttpURLConnection connectionMock, ProjectFilesystem projectFilesystem,
        BuckEventBus buckEventBus) {
      super("localhost", 8080, 1, true, projectFilesystem, buckEventBus);
      this.connectionMock = connectionMock;
    }

    protected HttpURLConnection getConnection(String url)
        throws MalformedURLException, IOException {
      return connectionMock;
    }
  }
}
