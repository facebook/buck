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

package com.facebook.buck.python;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryDescription implements Description<PythonBinaryDescription.Arg> {

  public static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(System.getProperty("buck.buck_dir", System.getProperty("user.dir")))
          .resolve("src/com/facebook/buck/python/pex.py");

  public static final BuildRuleType TYPE = new BuildRuleType("python_binary");

  private final Path pathToPex;

  public PythonBinaryDescription(Path pathToPex) {
    this.pathToPex = Preconditions.checkNotNull(pathToPex);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> PythonBinary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    String mainName = args.main.getName();
    Path mainModule = params.getBuildTarget().getBasePath().resolve(mainName);

    // Build up the list of all components going into the python binary.
    PythonPackageComponents binaryPackageComponents = new PythonPackageComponents(
        /* modules */ ImmutableMap.of(mainModule, args.main),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ ImmutableMap.<Path, SourcePath>of());
    PythonPackageComponents allPackageComponents = PythonUtil.getAllComponents(
        params,
        binaryPackageComponents);

    // Return a build rule which builds the PEX, depending on everything that builds any of
    // the components.
    BuildRuleParams binaryParams = params.copyWithDeps(
        PythonUtil.getDepsFromComponents(allPackageComponents),
        ImmutableSortedSet.<BuildRule>of());
    return new PythonBinary(
        binaryParams,
        pathToPex,
        mainModule,
        allPackageComponents);
  }

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    public SourcePath main;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

}
