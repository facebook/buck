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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.FileHashCache;
import com.google.common.hash.HashCode;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class HttpArtifactCacheTest {

  private HttpArtifactCache cache;
  private HttpURLConnection connection;
  private ProjectFilesystem projectFilesystem;
  private BuckEventBus buckEventBus;
  private FileHashCache fileHashCache;

  private byte[] createFileContentsWithHashCode(HashCode code, String contents) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ObjectOutputStream objectStream = new ObjectOutputStream(output);
    objectStream.writeObject(code);
    output.write(contents.getBytes());
    return output.toByteArray();
  }

  @Before
  public void setUp() {
    connection = createNiceMock(HttpURLConnection.class);
    projectFilesystem = createMock(ProjectFilesystem.class);
    buckEventBus = BuckEventBusFactory.newInstance();
    fileHashCache = createMock(FileHashCache.class);
    cache = new FakeHttpArtifactCache(connection, projectFilesystem, buckEventBus, fileHashCache);
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
    String data = "test";
    HashCode hashCode = HashCode.fromString("deadbeef");
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_OK);
    InputStream is = new ByteArrayInputStream(createFileContentsWithHashCode(hashCode, data));
    expect(connection.getInputStream()).andReturn(is);
    File file = File.createTempFile("000", "");
    Path path = file.toPath();
    Path temp = File.createTempFile("000", "").toPath();
    projectFilesystem.createParentDirs(path);
    expect(projectFilesystem.createTempFile(
            path.getParent(),
            path.getFileName().toString(),
            ".tmp"))
        .andReturn(temp);
    projectFilesystem.copyToPath(is, temp, StandardCopyOption.REPLACE_EXISTING);
    expect(fileHashCache.get(temp)).andReturn(hashCode);
    projectFilesystem.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    replay(fileHashCache);
    replay(connection);
    replay(projectFilesystem);
    assertEquals(
        cache.fetch(new RuleKey("00000000000000000000000000000000"), file),
        CacheResult.HTTP_HIT);
    verify(fileHashCache);
    verify(connection);
    verify(projectFilesystem);
  }

  @Test
  public void testFetchBadChecksum() throws IOException {
    String data = "test";
    HashCode hashCode = HashCode.fromString("deadbeef");
    HashCode badHashCode = HashCode.fromString("deafbead");
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_OK);
    InputStream is = new ByteArrayInputStream(createFileContentsWithHashCode(hashCode, data));
    expect(connection.getInputStream()).andReturn(is);
    File file = File.createTempFile("000", "");
    Path path = file.toPath();
    Path temp = File.createTempFile("000", "").toPath();
    projectFilesystem.createParentDirs(path);
    expect(projectFilesystem.createTempFile(
            path.getParent(),
            path.getFileName().toString(),
            ".tmp"))
        .andReturn(temp);
    projectFilesystem.copyToPath(is, temp, StandardCopyOption.REPLACE_EXISTING);
    expect(fileHashCache.get(temp)).andReturn(badHashCode);
    expect(projectFilesystem.deleteFileAtPath(temp)).andReturn(true);
    replay(fileHashCache);
    replay(connection);
    replay(projectFilesystem);
    assertEquals(
        cache.fetch(new RuleKey("00000000000000000000000000000000"), file),
        CacheResult.MISS);
    verify(fileHashCache);
    verify(connection);
    verify(projectFilesystem);
  }

  @Test
  public void testStore() throws IOException {
    String data = "test";
    HashCode hashCode = HashCode.fromString("deadbeef");
    connection.setConnectTimeout(1000);
    connection.setDoOutput(true);
    connection.setRequestMethod("POST");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    expect(connection.getOutputStream()).andReturn(output);
    File file = File.createTempFile("000", "");
    expect(fileHashCache.get(file.toPath())).andReturn(hashCode);
    InputStream is = new ByteArrayInputStream(data.getBytes());
    expect(projectFilesystem.newFileInputStream(file.toPath())).andReturn(is);
    expect(connection.getResponseCode()).andReturn(HttpURLConnection.HTTP_ACCEPTED);
    replay(fileHashCache);
    replay(connection);
    replay(projectFilesystem);
    cache.store(new RuleKey("00000000000000000000000000000000"), file);
    verify(fileHashCache);
    verify(connection);
    verify(projectFilesystem);
    assertNotEquals(
        -1,
        output.toString().indexOf(new String(createFileContentsWithHashCode(hashCode, data))));
  }

  class FakeHttpArtifactCache extends HttpArtifactCache {
    private HttpURLConnection connectionMock;

    FakeHttpArtifactCache(
        HttpURLConnection connectionMock,
        ProjectFilesystem projectFilesystem,
        BuckEventBus buckEventBus,
        FileHashCache fileHashCache) {
      super("localhost", 8080, 1, true, projectFilesystem, buckEventBus, fileHashCache);
      this.connectionMock = connectionMock;
    }

    @Override
    protected HttpURLConnection getConnection(String url)
        throws MalformedURLException, IOException {
      return connectionMock;
    }
  }
}
