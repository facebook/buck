/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.filesystems.BuckFileSystem;
import com.facebook.buck.testutil.BuckFSProviderDeleteError;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link AsynchronousDirectoryContentsCleaner}. */
public class AsynchronousDirectoryContentsCleanerTest {

  @Test
  public void contentsOfTrashDirectoryCleanedAsynchronouslyAllDeleted() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path dirToDelete = vfs.getPath("/tmp/fake-tmp-dir");
    Path fooDir = dirToDelete.resolve("foo");
    Path fooBarDir = fooDir.resolve("bar");
    Files.createDirectories(fooBarDir.resolve("baz"));
    Path fooBarBlechTxtFile = fooBarDir.resolve("blech.txt");
    Files.write(fooBarBlechTxtFile, "hello world\n".getBytes(UTF_8));

    assertThat(Files.exists(dirToDelete), is(true));
    assertThat(Files.exists(fooBarDir), is(true));
    assertThat(Files.exists(fooBarBlechTxtFile), is(true));

    AsynchronousDirectoryContentsCleaner cleaner =
        new AsynchronousDirectoryContentsCleaner(MoreExecutors.directExecutor());

    // This executes synchronously, since we passed in a direct executor above.
    cleaner.startCleaningDirectory(dirToDelete);

    assertThat(Files.exists(dirToDelete), is(true));
    assertThat(Files.exists(fooDir), is(false));
    assertThat(Files.exists(fooBarBlechTxtFile), is(false));
  }

  /*
   The test directory structure here looks like this, with undeletable marked with RO
   fake-tmp-dir
     foo
       bar
         baz
         blech.txt
       ack0.txt
       ack1.txt
       ack2.txt (RO)
       ack3.txt
       ack4.txt
       bak (RO)

   After deletion, we expect
   fake-tmp-dir
     foo
       ack2.txt
       bak
  */
  @Test
  public void contentsOfTrashDirectoryCleanedAsynchronouslySomeLeft() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    BuckFSProviderDeleteError bfsProvider = new BuckFSProviderDeleteError(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");

    Path dirToDelete = bfs.getPath("/tmp/fake-tmp-dir");
    Path fooDir = dirToDelete.resolve("foo");
    Path fooBarDir = fooDir.resolve("bar");
    Files.createDirectories(fooBarDir.resolve("baz"));
    Path bakDir = fooDir.resolve("bak");
    Files.createDirectories(bakDir);

    List<Path> ackPaths = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      Path path = fooDir.resolve("ack" + i + ".txt");
      ackPaths.add(path);
      Files.write(path, "hello world\n".getBytes(UTF_8));
    }
    bfsProvider.setFileToErrorOnDeletion(ackPaths.get(2));
    bfsProvider.setFileToErrorOnDeletion(bakDir);

    Path fooBarBlechTxtFile = fooBarDir.resolve("blech.txt");
    Files.write(fooBarBlechTxtFile, "hello world\n".getBytes(UTF_8));

    AsynchronousDirectoryContentsCleaner cleaner =
        new AsynchronousDirectoryContentsCleaner(MoreExecutors.directExecutor());

    assertThat(Files.exists(dirToDelete), is(true));
    assertThat(Files.exists(fooBarDir), is(true));
    assertThat(Files.exists(fooBarBlechTxtFile), is(true));

    // This executes synchronously, since we passed in a direct executor above.
    cleaner.startCleaningDirectory(dirToDelete);

    assertThat(Files.exists(dirToDelete), is(true));
    assertThat(Files.exists(fooBarDir), is(false));
    assertThat(Files.exists(fooDir), is(true));
    assertThat(Files.exists(fooBarBlechTxtFile), is(false));

    for (int i = 0; i < 5; i++) {
      boolean exists = Files.exists(ackPaths.get(i));
      if (i == 2) {
        assertThat(exists, is(true));
      } else {
        assertThat(exists, is(false));
      }
    }
  }
}
