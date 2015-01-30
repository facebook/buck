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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryDescription implements Description<PythonBinaryDescription.Arg> {

  public static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(
          System.getProperty(
              "buck.path_to_pex",
              "src/com/facebook/buck/python/pex.py"))
          .toAbsolutePath();

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("python_binary");

  private final Path pathToPex;
  private final PythonEnvironment pythonEnvironment;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonBinaryDescription(
      Path pathToPex,
      PythonEnvironment pythonEnv,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pathToPex = pathToPex;
    this.pythonEnvironment = pythonEnv;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
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

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatforms
          .getValue(ImmutableSet.copyOf(params.getBuildTarget().getFlavors()))
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    String mainName = pathResolver.getSourcePathName(params.getBuildTarget(), args.main);
    Path mainModule = baseModule.resolve(mainName);

    // Build up the list of all components going into the python binary.
    PythonPackageComponents binaryPackageComponents = ImmutablePythonPackageComponents.of(
        /* modules */ ImmutableMap.of(mainModule, args.main),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ ImmutableMap.<Path, SourcePath>of());
    PythonPackageComponents allPackageComponents = PythonUtil.getAllComponents(
        params,
        binaryPackageComponents,
        cxxPlatform);

    // Return a build rule which builds the PEX, depending on everything that builds any of
    // the components.
    BuildRuleParams binaryParams = params.copyWithDeps(
        Suppliers.ofInstance(PythonUtil.getDepsFromComponents(pathResolver, allPackageComponents)),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    return new PythonBinary(
        binaryParams,
        pathResolver,
        pathToPex,
        pythonEnvironment,
        mainModule,
        allPackageComponents);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public SourcePath main;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<String> baseModule;
  }

}
