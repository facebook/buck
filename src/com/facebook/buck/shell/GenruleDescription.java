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
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.regex.Matcher;

public class GenruleDescription
    implements Description<GenruleDescription.Arg>, ImplicitDepsInferringDescription {

  public static final BuildRuleType TYPE = new BuildRuleType("genrule");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public Genrule createBuildable(BuildRuleParams params, Arg args) {
    return new Genrule(
        params.getBuildTarget(),
        args.srcs.get(),
        args.cmd,
        args.bash,
        args.cmdExe,
        args.out,
        params.getPathAbsolutifier());
  }

  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    Object rawCmd = params.getNullableRawAttribute("cmd");
    if (rawCmd == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> targets = ImmutableList.builder();
    Matcher matcher = AbstractGenruleStep.BUILD_TARGET_PATTERN.matcher(((String) rawCmd));
    while (matcher.find()) {
      targets.add(matcher.group(3));
    }
    return targets.build();
  }

  public class Arg implements ConstructorArg {
    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<Path>> srcs;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
