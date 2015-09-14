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

package com.facebook.buck.shell;


import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class GenruleDescription extends AbstractGenruleDescription<AbstractGenruleDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("genrule");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  protected <A extends Arg> BuildRule createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args,
      ImmutableList<SourcePath> srcs,
      Function<String, String> macroExpander,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      final Function<Path, Path> relativeToAbsolutePathFunction,
      Supplier<ImmutableList<Object>> macroRuleKeyAppendables) {
    return new Genrule(
        params,
        new SourcePathResolver(resolver),
        srcs,
        macroExpander,
        cmd,
        bash,
        cmdExe,
        out,
        relativeToAbsolutePathFunction,
        macroRuleKeyAppendables);
  }

}
