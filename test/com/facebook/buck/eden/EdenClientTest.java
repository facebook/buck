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
import static org.junit.Assert.assertNull;

import com.facebook.eden.EdenError;
import com.facebook.eden.EdenService;
import com.facebook.eden.MountInfo;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class EdenClientTest {

  private EdenService.Client thriftClient;
  private List<MountInfo> mountInfos;

  @Before
  public void setUp() throws EdenError, TException {
    thriftClient = createMock(EdenService.Client.class);
    mountInfos = ImmutableList.of(
      new MountInfo("/home/mbolin/src/buck", /* edenClientPath */ ""),
      new MountInfo("/home/mbolin/src/eden", /* edenClientPath */ ""));
    expect(thriftClient.listMounts()).andReturn(mountInfos);
  }

  @After
  public void tearDown() {
    verify(thriftClient);
  }

  @Test
  public void getMountInfosDelegatesToThriftClient() throws EdenError, TException {
    replay(thriftClient);

    EdenClient client = new EdenClient(thriftClient);
    assertEquals(mountInfos, client.getMountInfos());
  }

  @Test
  public void getMountForReturnsMatchingMountInfo() throws EdenError, TException {
    replay(thriftClient);

    EdenClient client = new EdenClient(thriftClient);
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path eden = fs.getPath("/home/mbolin/src/eden");

    EdenMount mount = client.getMountFor(eden);
    assertEquals(Paths.get("/home/mbolin/src/eden"), mount.getMountPoint());
  }

  @Test
  public void getMountForThrowsWhenMissingMountPoint() throws EdenError, TException {
    replay(thriftClient);

    EdenClient client = new EdenClient(thriftClient);
    EdenMount mount = client.getMountFor(Paths.get("/home/mbolin/src/other_project"));
    assertNull(mount);
  }
}
