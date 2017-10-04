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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

public class GenruleDescription extends AbstractGenruleDescription<GenruleDescriptionArg> {

  @Override
  public Class<GenruleDescriptionArg> getConstructorArgType() {
    return GenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GenruleDescriptionArg args,
      Optional<com.facebook.buck.rules.args.Arg> cmd,
      Optional<com.facebook.buck.rules.args.Arg> bash,
      Optional<com.facebook.buck.rules.args.Arg> cmdExe) {
    if (!args.getExecutable().orElse(false)) {
      return new Genrule(
          buildTarget,
          projectFilesystem,
          params,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut());
    } else {
      return new GenruleBinary(
          buildTarget,
          projectFilesystem,
          params,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut());
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    Optional<Boolean> getExecutable();
  }
}
