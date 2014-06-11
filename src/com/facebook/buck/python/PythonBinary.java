/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PythonBinary extends AbstractBuildable implements BinaryBuildRule {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  private final ImmutableSortedSet<BuildRule> deps;
  private final Path main;

  protected PythonBinary(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      Path main) {
    super(target);
    this.deps = Preconditions.checkNotNull(deps);
    this.main = Preconditions.checkNotNull(main);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public Path getBinPath() {
    return BuildTargets.getBinPath(getBuildTarget(), "%s.pex");
  }

  @Override
  public Path getPathToOutputFile() {
    return getBinPath();
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(
        Preconditions.checkNotNull(
            projectFilesystem.getAbsolutifier().apply(getBinPath())).toString());
  }

  @VisibleForTesting
  protected PythonPackageComponents getAllComponents() {
    final PythonPackageComponents.Builder components =
        new PythonPackageComponents.Builder(getBuildTarget().toString());

    // Add our main module.
    components.addModule(main, main, getBuildTarget().toString());

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractDependencyVisitor(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        Buildable buildable = rule.getBuildable();

        // We only process and recurse on instances of PythonPackagable.
        if (buildable instanceof PythonPackagable) {
          PythonPackagable lib = (PythonPackagable) buildable;

          // Add all components from the python packable into our top-level
          // package.
          components.addComponent(
              lib.getPythonPackageComponents(),
              rule.getBuildTarget().toString());

          // Return all our deps to recurse on them.
          return rule.getDeps();
        }

        // Don't recurse on anything from other rules.
        return ImmutableSet.of();
      }
    }.start();

    return components.build();
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(main);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("packageType", "pex")
        .setInput("mainModule", main);
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  private String toModuleName(Path modulePath) {
    String name = modulePath.toString();
    int ext = name.lastIndexOf('.');
    if (ext == -1) {
      throw new HumanReadableException(
          "%s: missing extension for module path: %s",
          getBuildTarget(),
          modulePath);
    }
    name = name.substring(0, ext);
    return MorePaths.pathWithUnixSeparators(name).replace('/', '.');
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path binPath = getBinPath();

    // Make sure the parent directory exists.
    steps.add(new MkdirStep(binPath.getParent()));

    // Generate and return the PEX build step.
    PythonPackageComponents components = getAllComponents();
    steps.add(new PexStep(
        binPath,
        toModuleName(main),
        components.getModules(),
        components.getResources()));

    // Record the executable package for caching.
    buildableContext.recordArtifact(getBinPath());

    return steps.build();
  }

}
