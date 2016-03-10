/*
 * Copyright 2016-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *  test
 */

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;

public class WorkerToolDescription implements Description<WorkerToolDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("worker_tool");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public WorkerToolDescription.Arg createUnpopulatedConstructorArg() {
    return new WorkerToolDescription.Arg();
  }

  @Override
  public <A extends Arg> WorkerTool createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    BuildRule rule = resolver.requireRule(args.exe);
    if (!(rule instanceof BinaryBuildRule)) {
      throw new HumanReadableException("The 'exe' argument of %s, %s, needs to correspond to a " +
          "binary rule, such as sh_binary().",
          params.getBuildTarget(),
          args.exe.getFullyQualifiedName());
    }

    return new WorkerTool(
        params,
        new SourcePathResolver(resolver),
        (BinaryBuildRule) rule,
        args.args.or(""));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> args;
    public BuildTarget exe;
  }
}
