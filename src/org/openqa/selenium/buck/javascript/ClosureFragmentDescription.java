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

package org.openqa.selenium.buck.javascript;


import static java.lang.Boolean.FALSE;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class ClosureFragmentDescription implements
    Description<ClosureFragmentDescription.Arg>,
    ImplicitDepsInferringDescription<ClosureFragmentDescription.Arg> {

  private final JavascriptConfig config;

  public ClosureFragmentDescription(JavascriptConfig config) {
    this.config = config;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder finder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(finder);
    return new JsFragment(
        params,
        config.getClosureCompiler(args.compiler, pathResolver, finder),
        params.getBuildDeps(),
        args.module,
        args.function,
        args.defines,
        args.prettyPrint.orElse(FALSE));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      ClosureFragmentDescription.Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        RichStream.of(config.getClosureCompilerSourcePath(constructorArg.compiler))
            .filter(BuildTargetSourcePath.class)
            .map(BuildTargetSourcePath::getTarget)
            .collect(MoreCollectors.toImmutableList()));
  }

  public static class Arg extends AbstractDescriptionArg {
    public String function;
    public String module;
    public Optional<Boolean> prettyPrint;
    public ImmutableList<String> defines = ImmutableList.of();
    public Optional<SourcePath> compiler;

    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  }
}
