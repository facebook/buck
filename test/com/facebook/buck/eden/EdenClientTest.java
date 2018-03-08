/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.eden;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class EdenClientTest {

  private EdenClient thriftClient;
  private FileSystem fs;
  private EdenClientPool pool;

  @Before
  public void setUp() {
    thriftClient = createMock(EdenClient.class);
    fs = Jimfs.newFileSystem(Configuration.unix());
    pool = new EdenClientPool(thriftClient);
  }

  @Test
  public void getMountInfosDelegatesToThriftClient() throws EdenError, IOException, TException {
    List<MountInfo> mountInfos =
        ImmutableList.of(
            new MountInfo("/home/mbolin/src/buck", /* edenClientPath */ ""),
            new MountInfo("/home/mbolin/src/eden", /* edenClientPath */ ""));
    expect(thriftClient.listMounts()).andReturn(mountInfos);
    replay(thriftClient);
    assertEquals(mountInfos, pool.getClient().listMounts());
    verify(thriftClient);
  }

  @Test
  public void getMountForMatchesProjectRootEqualToMount() throws IOException {
    Path projectRoot = fs.getPath("/home/mbolin/src/eden");
    Files.createDirectories(projectRoot.resolve(".eden"));
    Files.createSymbolicLink(projectRoot.resolve(".eden").resolve("root"), projectRoot);

    Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(projectRoot, pool);
    assertTrue("Should find mount for path.", mount.isPresent());
    assertEquals(fs.getPath("/home/mbolin/src/eden"), mount.get().getProjectRoot());
    assertEquals(fs.getPath(""), mount.get().getPrefix());
  }

  @Test
  public void getMountForMatchesProjectRootUnderMount() throws IOException {
    Path edenMountRoot = fs.getPath("/home/mbolin/src/eden");
    Path projectRoot = fs.getPath("/home/mbolin/src/eden/deep/project");
    Files.createDirectories(projectRoot.resolve(".eden"));
    Files.createSymbolicLink(projectRoot.resolve(".eden").resolve("root"), edenMountRoot);

    Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(projectRoot, pool);
    assertTrue("Should find mount for path.", mount.isPresent());
    assertEquals(projectRoot, mount.get().getProjectRoot());
    assertEquals(fs.getPath("deep/project"), mount.get().getPrefix());
  }

  @Test
  public void getMountForReturnsNullWhenMissingMountPoint() {
    Path projectRoot = Paths.get("/home/mbolin/src/other_project");
    Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(projectRoot, pool);
    assertFalse(mount.isPresent());
  }
}
