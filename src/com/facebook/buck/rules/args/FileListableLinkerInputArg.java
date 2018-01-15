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
package com.facebook.buck.rules.args;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Arg that represents object file that should be linked into resulting binary using normal
 * mechanism, e.g. passed to the linker without any additional surrounding flags. Sometimes these
 * arguments can be grouped into a single text file that linker can pick up. This is mostly to
 * simplify and shorten command line that is used to invoke the linker. This arg represents such
 * kind of object file in the list of args, so later we can easily create such file list for the
 * linker.
 */
public class FileListableLinkerInputArg implements Arg, HasSourcePath {
  @AddToRuleKey private final SourcePathArg value;

  public static Arg withSourcePathArg(SourcePathArg value) {
    return new FileListableLinkerInputArg(value);
  }

  public static ImmutableList<Arg> from(ImmutableList<SourcePathArg> values) {
    ImmutableList.Builder<Arg> converted = ImmutableList.builder();
    for (SourcePathArg arg : values) {
      converted.add(withSourcePathArg(arg));
    }
    return converted.build();
  }

  private FileListableLinkerInputArg(SourcePathArg value) {
    this.value = value;
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    value.appendToCommandLine(consumer, pathResolver);
  }

  public void appendToCommandLineRel(
      Consumer<String> consumer, Path currentCellPath, SourcePathResolver pathResolver) {
    value.appendToCommandLineRel(consumer, currentCellPath, pathResolver);
  }

  @Override
  public String toString() {
    return value.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FileListableLinkerInputArg)) {
      return false;
    }

    FileListableLinkerInputArg a = (FileListableLinkerInputArg) other;
    return value.equals(a.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public SourcePath getPath() {
    return value.getPath();
  }
}
