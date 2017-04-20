/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JsBinary extends AbstractBuildRule implements
    InitializableFromDisk<JavascriptDependencies>, HasJavascriptDependencies {

  private final Path joyPath;
  private final Path output;
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final com.google.common.base.Supplier<ImmutableSortedSet<BuildRule>> deps;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableList<String> defines;
  @AddToRuleKey
  private final ImmutableList<SourcePath> externs;
  @AddToRuleKey
  private final ImmutableList<String> flags;
  @AddToRuleKey
  private final boolean prettyPrint;
  private JavascriptDependencies joy;
  private final BuildOutputInitializer<JavascriptDependencies> buildOutputInitializer;

  public JsBinary(
      BuildRuleParams params,
      Tool compiler,
      com.google.common.base.Supplier<ImmutableSortedSet<BuildRule>> deps,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableList<String> defines,
      ImmutableList<String> flags,
      ImmutableList<SourcePath> externs,
      Optional<Boolean> noFormat) {
    super(params);

    this.compiler = compiler;

    this.deps = Preconditions.checkNotNull(deps);
    this.srcs = Preconditions.checkNotNull(srcs);
    this.defines = Preconditions.checkNotNull(defines);
    this.externs = Preconditions.checkNotNull(externs);
    this.flags = Preconditions.checkNotNull(flags);
    this.prettyPrint = !noFormat.orElse(Boolean.FALSE);  // Bloody double negatives

    this.output = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.js");
    this.joyPath = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.deps");

    buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    JavascriptDependencyGraph graph = new JavascriptDependencyGraph();

    // Iterate over deps and build a bundle of joy
    JavascriptDependencies smidgen = new JavascriptDependencies();

    Set<String> jsDeps = Sets.newHashSet();
    Set<JavascriptSource> jsSources = Sets.newHashSet();
    // Do the magic with the sources, as if we're a js_library
    for (SourcePath src : srcs) {
      Path resolved = context.getSourcePathResolver().getAbsolutePath(src);
      JavascriptSource jsSource = new JavascriptSource(resolved);
      jsSources.add(jsSource);

      graph.amendGraph(jsSource);
      smidgen.add(jsSource);
      jsDeps.addAll(jsSource.getRequires());
    }

    Set<String> seen = Sets.newHashSet();
    for (BuildRule dep : deps.get()) {
      if (!(dep instanceof HasJavascriptDependencies)) {
        continue;
      }

      JavascriptDependencies moreJoy = ((HasJavascriptDependencies) dep).getBundleOfJoy();

      for (String require : jsDeps) {
        Set<JavascriptSource> sources = moreJoy.getDeps(require);
        if (!seen.contains(require) && !sources.isEmpty()) {
          smidgen.addAll(sources);
          graph.amendGraph(sources);
          seen.add(require);
        }
      }
    }

    // Now ask that mess of data for the things we need.
    for (String dep : jsDeps) {
      smidgen.amendGraph(graph, dep);
    }

    // If the srcs don't goog.provide anything, they won't be included in the sources. Which is an
    // oversight.
    LinkedHashSet<Path> requiredSources = new LinkedHashSet<>();
    requiredSources.addAll(graph.sortSources());
    for (JavascriptSource jsSource : jsSources) {
      if (jsSource.getProvides().isEmpty()) {
        requiredSources.add(jsSource.getPath());
      }
    }

    ClosureCompilerStep compileStep =
        ClosureCompilerStep.builder(getProjectFilesystem().getRootPath(), context.getSourcePathResolver(), compiler)
            .defines(defines)
            .externs(externs)
            .flags(flags)
            .prettyPrint(prettyPrint)
            .sources(requiredSources)
            .output(output)
            .build();

    StringWriter writer = new StringWriter();
    smidgen.writeTo(writer);

    steps.add(MkdirStep.of(getProjectFilesystem(), joyPath.getParent()));
    steps.add(new WriteFileStep(getProjectFilesystem(), writer.toString(), joyPath, false));
    steps.add(MkdirStep.of(getProjectFilesystem(), output.getParent()));
    steps.add(compileStep);

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new PathSourcePath(getProjectFilesystem(), output);
  }

  @Override
  public JavascriptDependencies initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    try {
      List<String> allLines = onDiskBuildInfo.getOutputFileContentsByLine(joyPath);
      joy = JavascriptDependencies.buildFrom(Joiner.on("\n").join(allLines));
      return joy;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public BuildOutputInitializer<JavascriptDependencies> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public JavascriptDependencies getBundleOfJoy() {
    return joy;
  }
}
