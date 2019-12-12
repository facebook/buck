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

package com.facebook.buck.jvm.java;

import com.google.common.io.Files;
import difflib.DiffUtils;
import difflib.Patch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public final class JarDiffer {
  private final Path left;
  private final Path right;
  private final JarDumper jarDumper = new JarDumper();

  public static List<String> diffJars(Path left, Path right) throws IOException {
    return new JarDiffer(left, right).diff();
  }

  public JarDiffer(Path left, Path right) {
    this.left = left;
    this.right = right;
  }

  public JarDiffer setAsmFlags(int asmFlags) {
    jarDumper.setAsmFlags(asmFlags);
    return this;
  }

  public List<String> diff() throws IOException {
    if (Files.equal(left.toFile(), right.toFile())) {
      return Collections.emptyList();
    }

    List<String> leftDump = jarDumper.dump(left);
    List<String> rightDump = jarDumper.dump(right);

    Patch<String> diff = DiffUtils.diff(leftDump, rightDump);
    return DiffUtils.generateUnifiedDiff(left.toString(), right.toString(), leftDump, diff, 4);
  }
}
