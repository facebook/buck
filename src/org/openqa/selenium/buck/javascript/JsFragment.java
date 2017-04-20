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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public class JsFragment extends AbstractBuildRule {

  private final Path output;
  private final Path temp;
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final ImmutableSortedSet<BuildRule> deps;
  @AddToRuleKey
  private final String module;
  @AddToRuleKey
  private final String function;
  @AddToRuleKey
  private final ImmutableList<String> defines;
  @AddToRuleKey
  private final boolean prettyPrint;

  public JsFragment(
      BuildRuleParams params,
      Tool compiler,
      ImmutableSortedSet<BuildRule> deps,
      String module,
      String function,
      ImmutableList<String> defines,
      boolean prettyPrint) {
    super(params);

    this.deps = deps;
    this.module = module;
    this.function = function;
    this.output = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.js");
    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-temp.js");
    this.compiler = compiler;
    this.defines = defines;
    this.prettyPrint = prettyPrint;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    JavascriptDependencyGraph graph = new JavascriptDependencyGraph();

    for (BuildRule dep : deps) {
      if (!(dep instanceof JsLibrary)) {
        continue;
      }

      Set<JavascriptSource> allDeps = ((JsLibrary) dep).getBundleOfJoy().getDeps(module);
      if (!allDeps.isEmpty()) {
        graph.amendGraph(allDeps);
        break;
      }
    }

    steps.add(MkdirStep.of(getProjectFilesystem(), temp.getParent()));
    steps.add(
        new WriteFileStep(
            getProjectFilesystem(),
            String.format("goog.require('%s'); goog.exportSymbol('_', %s);", module, function),
            temp,
            /* executable */ false));
    steps.add(MkdirStep.of(getProjectFilesystem(), output.getParent()));

    List<String> flags = defines.stream()
        .map(define -> "--define=" + define)
        .collect(Collectors.toList());

    if (prettyPrint) {
      flags.add("--formatting=PRETTY_PRINT");
    }
    steps.add(
        new JavascriptFragmentStep(
            getProjectFilesystem().getRootPath(),
            context.getSourcePathResolver(),
            compiler,
            ImmutableList.copyOf(flags),
            temp,
            output,
            graph.sortSources()));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return new PathSourcePath(getProjectFilesystem(), output);
  }

}
