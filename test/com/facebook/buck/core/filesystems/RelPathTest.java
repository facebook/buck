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

package com.facebook.buck.core.filesystems;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class RelPathTest {

  @Test
  public void ofPath() {
    for (String path : new String[] {"", "a", "a/bbb", "././aa/.."}) {
      assertThat(RelPath.get(path), equalTo(RelPath.of(Paths.get(path))));
    }
  }

  @Test
  public void relPath() throws IOException {
    Path absPath = BuckUnixPathUtils.createPath("/a/b/c");
    Path relPath = absPath.relativize(BuckUnixPathUtils.createPath("/a/b/c/Test.txt"));
    assertSame(RelPath.of(relPath), relPath);
  }

  @Test
  public void absPath() throws IOException {
    Path absPath = Paths.get(".").normalize().toAbsolutePath();

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> RelPath.of(absPath));
    assertThat(exception.getMessage(), startsWith("path must be relative: "));
  }
}
