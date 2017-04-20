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
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class JsLibrary extends AbstractBuildRule implements
    InitializableFromDisk<JavascriptDependencies>, HasJavascriptDependencies {

  private final Path output;
  @AddToRuleKey
  private final ImmutableSortedSet<BuildRule> deps;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  private JavascriptDependencies joy;
  private final BuildOutputInitializer<JavascriptDependencies> buildOutputInitializer;

  public JsLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<SourcePath> srcs) {
    super(params);
    this.deps = Preconditions.checkNotNull(deps);
    this.srcs = Preconditions.checkNotNull(srcs);

    this.output = BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "%s-library.deps");

    buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Set<String> allRequires = Sets.newHashSet();
    Set<String> allProvides = Sets.newHashSet();
    JavascriptDependencies smidgen = new JavascriptDependencies();
    for (SourcePath src : srcs) {
      Path path = context.getSourcePathResolver().getAbsolutePath(src);
      JavascriptSource source = new JavascriptSource(path);
      smidgen.add(source);
      allRequires.addAll(source.getRequires());
      allProvides.addAll(source.getProvides());
    }

    allRequires.removeAll(allProvides);

    for (BuildRule dep : deps) {
      Iterator<String> iterator = allRequires.iterator();

      if (!(dep instanceof HasJavascriptDependencies)) {
        continue;
      }
      JavascriptDependencies moreJoy = ((HasJavascriptDependencies) dep).getBundleOfJoy();

      while (iterator.hasNext()) {
        String require = iterator.next();

        Set<JavascriptSource> sources = moreJoy.getDeps(require);
        if (!sources.isEmpty()) {
          smidgen.addAll(sources);
          iterator.remove();
        }
      }
    }

    if (!allRequires.isEmpty()) {
      throw new RuntimeException(
          getBuildTarget() + " --- Missing dependencies for: " + allRequires);
    }

    StringWriter writer = new StringWriter();
    smidgen.writeTo(writer);

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    builder.add(MkdirStep.of(getProjectFilesystem(), output.getParent()));
    builder.add(new WriteFileStep(getProjectFilesystem(), writer.toString(), output, false));

    buildableContext.recordArtifact(output);

    return builder.build();
  }

  @Override
  public JavascriptDependencies initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    try {
      List<String> allLines = onDiskBuildInfo.getOutputFileContentsByLine(output);
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
  public SourcePath getSourcePathToOutput() {
    return new PathSourcePath(getProjectFilesystem(), output);
  }

  @Override
  public JavascriptDependencies getBundleOfJoy() {
    return joy;
  }
}
