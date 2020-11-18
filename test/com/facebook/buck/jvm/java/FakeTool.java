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

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Fake implementation of {@link Tool} for tests. */
public class FakeTool implements Tool {

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
    return ImmutableMap.of();
  }
}
