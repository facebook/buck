/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

/**
 * A {@link Tool} which only contributes a fixed name and version when appended to a rule key.
 * This class also holds a {@link SourcePath} reference to the tool and additional flags used
 * when invoking the tool, which do *not* themselves contribute to the rule key.  This is useful
 * in situations such as supporting cross-compilation, in which case the different tools themselves
 * might not be bit-by-bit identical (and, similarly, they might need to invoked with slightly
 * different flags) but we know that they produce identical output, in which case they should also
 * generate identical rule keys.
 */
public class VersionedTool implements Tool {

  /**
   * The path to the tool.  The contents or path to the tool do not contribute to the rule key.
   */
  private final SourcePath path;

  /**
   * Additional flags that we pass to the tool, but which do *not* contribute to the rule key.
   */
  private final ImmutableList<String> extraArgs;

  private final String name;
  private final String version;

  public VersionedTool(
      SourcePath path,
      ImmutableList<String> extraArgs,
      String name,
      String version) {
    this.path = path;
    this.extraArgs = extraArgs;
    this.name = name;
    this.version = version;
  }

  @Override
  public ImmutableList<BuildRule> getBuildRules(SourcePathResolver resolver) {
    return ImmutableList.copyOf(resolver.filterBuildRuleInputs(ImmutableList.of(path)));
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return ImmutableList.<String>builder()
        .add(resolver.getPath(path).toString())
        .addAll(extraArgs)
        .build();
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    return builder.setReflectively(key, String.format("%s (%s)", name, version));
  }

}
