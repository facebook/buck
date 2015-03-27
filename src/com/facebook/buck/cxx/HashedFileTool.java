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
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class HashedFileTool implements Tool {

  public static final Function<Path, HashedFileTool> FROM_PATH =
      new Function<Path, HashedFileTool>() {
        @Override
        public HashedFileTool apply(Path input) {
          return new HashedFileTool(input);
        }
      };

  private final Path path;

  public HashedFileTool(Path path) {
    this.path = path;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    return builder.setReflectively(key, path);
  }

  @Override
  public ImmutableList<BuildRule> getBuildRules(SourcePathResolver resolver) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return ImmutableList.of(path.toString());
  }

}
