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

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.EdenError;
import com.facebook.eden.EdenService;
import com.facebook.eden.MountInfo;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.Test;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class EdenMountTest {
  @Test
  public void getSha1DelegatesToThriftClient() throws EdenError, TException {
    List<MountInfo> mountInfos = ImmutableList.of(
        new MountInfo("/home/mbolin/src/buck", /* edenClientPath */ ""));
    EdenService.Client thriftClient = createMock(EdenService.Client.class);
    expect(thriftClient.listMounts()).andReturn(mountInfos);

    Path entry = Paths.get("LICENSE");
    HashCode hash = HashCode.fromString("2b8b815229aa8a61e483fb4ba0588b8b6c491890");
    expect(thriftClient.getSHA1("/home/mbolin/src/buck", "LICENSE")).andReturn(hash.asBytes());
    replay(thriftClient);

    EdenClient client = new EdenClient(thriftClient);
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path pathToBuck = fs.getPath("/home/mbolin/src/buck");
    EdenMount mount = client.getMountFor(pathToBuck);
    assertEquals(Sha1HashCode.fromHashCode(hash), mount.getSha1(entry));
    verify(thriftClient);
  }

  @Test
  public void getMountPointReturnsValuePassedToConstructor() {
    EdenService.Client thriftClient = createMock(EdenService.Client.class);
    Path mountPoint = Paths.get("/home/mbolin/src/buck");
    replay(thriftClient);

    EdenMount mount = new EdenMount(thriftClient, mountPoint);
    assertEquals(mountPoint, mount.getMountPoint());

    verify(thriftClient);
  }

  @Test
  public void toStringHasExpectedFormatting() {
    EdenService.Client thriftClient = createMock(EdenService.Client.class);
    Path mountPoint = Paths.get("/home/mbolin/src/buck");
    replay(thriftClient);

    EdenMount mount = new EdenMount(thriftClient, mountPoint);
    assertEquals(String.format("EdenMount{mountPoint=%s}", mountPoint), mount.toString());

    verify(thriftClient);
  }
}
