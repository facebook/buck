/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.io;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Unit tests for {@link AsynchronousDirectoryContentsCleaner}. */
public class AsynchronousDirectoryContentsCleanerTest {
  @Test
  public void contentsOfTrashDirectoryCleanedAsynchronously() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path dirToDelete = vfs.getPath("/tmp/fake-tmp-dir");
    Path fooDir = dirToDelete.resolve("foo");
    Path fooBarDir = fooDir.resolve("bar");
    Files.createDirectories(fooBarDir.resolve("baz"));

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
    assertThat(Files.exists(fooBarBlechTxtFile), is(false));
  }
}
