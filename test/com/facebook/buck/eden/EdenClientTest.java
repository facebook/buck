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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.EdenService;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EdenClientTest {

  private EdenService.Client thriftClient;
  private List<MountInfo> mountInfos;
  private FileSystem fs;
  private EdenClient client;

  @Before
  public void setUp() throws EdenError, TException {
    thriftClient = createMock(EdenService.Client.class);
    mountInfos =
        ImmutableList.of(
            new MountInfo("/home/mbolin/src/buck", /* edenClientPath */ ""),
            new MountInfo("/home/mbolin/src/eden", /* edenClientPath */ ""));
    expect(thriftClient.listMounts()).andReturn(mountInfos);
    replay(thriftClient);

    fs = Jimfs.newFileSystem(Configuration.unix());
    client = new EdenClient(thriftClient);
  }

  @After
  public void tearDown() {
    verify(thriftClient);
  }

  @Test
  public void getMountInfosDelegatesToThriftClient() throws EdenError, TException {
    assertEquals(mountInfos, client.getMountInfos());
  }

  @Test
  public void getMountForMatchesProjectRootEqualToMount() throws EdenError, TException {
    Path projectRoot = fs.getPath("/home/mbolin/src/eden");
    EdenMount mount = client.getMountFor(projectRoot);
    assertNotNull("Should find mount for path.", mount);
    assertEquals(fs.getPath("/home/mbolin/src/eden"), mount.getProjectRoot());
    assertEquals(fs.getPath(""), mount.getPrefix());
  }

  @Test
  public void getMountForMatchesProjectRootUnderMount() throws EdenError, TException {
    Path projectRoot = fs.getPath("/home/mbolin/src/eden/deep/project");
    EdenMount mount = client.getMountFor(projectRoot);
    assertNotNull("Should find mount for path.", mount);
    assertEquals(fs.getPath("/home/mbolin/src/eden/deep/project"), mount.getProjectRoot());
    assertEquals(fs.getPath("deep/project"), mount.getPrefix());
  }

  @Test
  public void getMountForReturnsNullWhenMissingMountPoint() throws EdenError, TException {
    EdenMount mount = client.getMountFor(Paths.get("/home/mbolin/src/other_project"));
    assertNull(mount);
  }
}
