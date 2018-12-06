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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

public class Groovyc implements Tool {

  public static String BIN_GROOVYC = "bin/groovyc";

  @AddToRuleKey private final Supplier<? extends SourcePath> path;
  @AddToRuleKey private final boolean external;

  public Groovyc(Supplier<? extends SourcePath> path, boolean external) {
    this.path = path;
    this.external = external;
  }

  public Groovyc(SourcePath path, boolean external) {
    this(() -> Objects.requireNonNull(path), external);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    Path toolPath = resolver.getAbsolutePath(path.get());

    if (!external) {
      toolPath = toolPath.resolve(BIN_GROOVYC);
    }
    return ImmutableList.of(toolPath.toString());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return ImmutableMap.of();
  }
}
