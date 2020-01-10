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

package com.facebook.buck.infer;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A tool based on path to the distribution directory and the binary name within this directory.
 *
 * <p>Infer is not a standalone binary, it is usually distributed as a tarball that has a binary and
 * various dependencies (e.g. shared libs). To run infer from such distribution we need to specify
 * the whole distribution directory as a dependency for #nullsafe flavored targets.
 *
 * <p>In case when infer needs to be executed both locally and remotely, a cross platform
 * distribution should be provided.
 */
public class InferDistTool implements Tool {
  @AddToRuleKey private final Supplier<? extends SourcePath> path;
  @AddToRuleKey private final String binary;

  /**
   * @param path Path to the directory of infer distribution
   * @param binary Name of the infer binary within distribution folder
   */
  public InferDistTool(Supplier<? extends SourcePath> path, String binary) {
    this.path = MoreSuppliers.memoize(path);
    this.binary = binary;
  }

  public InferDistTool(SourcePath path, String binary) {
    this(() -> path, binary);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
    Path pathToBinary = resolver.getAbsolutePath(path.get()).resolve(this.binary);
    return ImmutableList.of(pathToBinary.toString());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
    return ImmutableMap.of();
  }
}
