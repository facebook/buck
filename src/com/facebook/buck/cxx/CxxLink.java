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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class CxxLink
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsInputBasedRuleKey {

  @AddToRuleKey
  private final Tool linker;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;
  // We need to make sure we sanitize paths in the arguments, so add them to the rule key
  // in `appendToRuleKey` where we can first filter the args through the sanitizer.
  private final ImmutableList<String> args;
  private final ImmutableSet<Path> frameworkRoots;
  private final DebugPathSanitizer sanitizer;

  public CxxLink(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool linker,
      Path output,
      ImmutableList<SourcePath> inputs,
      ImmutableList<String> args,
      ImmutableSet<Path> frameworkRoots,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    this.linker = linker;
    this.output = output;
    this.inputs = inputs;
    this.args = args;
    this.frameworkRoots = frameworkRoots;
    this.sanitizer = sanitizer;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively(
            "args",
            FluentIterable.from(args)
                .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
                .toList())
        .setReflectively(
            "frameworkRoots",
            FluentIterable.from(frameworkRoots)
                .transform(Functions.toStringFunction())
                .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
                .toList());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxLinkStep(
            linker.getCommandPrefix(getResolver()),
            output,
            args,
            frameworkRoots));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Tool getLinker() {
    return linker;
  }

  public Path getOutput() {
    return output;
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

}
