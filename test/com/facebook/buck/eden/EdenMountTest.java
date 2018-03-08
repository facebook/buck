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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class EdenMountTest {
  @Test
  public void getSha1DelegatesToThriftClient() throws EdenError, IOException, TException {
    EdenClient thriftClient = createMock(EdenClient.class);

    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path entry = fs.getPath("LICENSE");
    HashCode hash = HashCode.fromString("2b8b815229aa8a61e483fb4ba0588b8b6c491890");
    SHA1Result sha1Result = new SHA1Result();
    sha1Result.setSha1(hash.asBytes());
    expect(thriftClient.getSHA1("/home/mbolin/src/buck", ImmutableList.of("LICENSE")))
        .andReturn(ImmutableList.of(sha1Result));
    replay(thriftClient);

    EdenClientPool pool = new EdenClientPool(thriftClient);
    Path pathToBuck = fs.getPath("/home/mbolin/src/buck");
    Files.createDirectories(pathToBuck.resolve(".eden"));
    Files.createSymbolicLink(pathToBuck.resolve(".eden").resolve("root"), pathToBuck);

    Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(pathToBuck, pool);
    assertTrue("Should find mount for path.", mount.isPresent());
    assertEquals(Sha1HashCode.fromHashCode(hash), mount.get().getSha1(entry));
    verify(thriftClient);
  }

  @Test
  public void getMountPointReturnsValuePassedToConstructor() {
    EdenClient thriftClient = createMock(EdenClient.class);
    EdenClientPool pool = new EdenClientPool(thriftClient);
    Path mountPoint = Paths.get("/home/mbolin/src/buck");
    replay(thriftClient);

    EdenMount mount = new EdenMount(pool, mountPoint, mountPoint);
    assertEquals(mountPoint, mount.getProjectRoot());

    verify(thriftClient);
  }

  @Test
  public void toStringHasExpectedFormatting() {
    EdenClient thriftClient = createMock(EdenClient.class);
    EdenClientPool pool = new EdenClientPool(thriftClient);
    Path mountPoint = Paths.get("/home/mbolin/src/buck");
    replay(thriftClient);

    EdenMount mount = new EdenMount(pool, mountPoint, mountPoint);
    assertEquals(String.format("EdenMount{mountPoint=%s, prefix=}", mountPoint), mount.toString());

    verify(thriftClient);
  }
}
