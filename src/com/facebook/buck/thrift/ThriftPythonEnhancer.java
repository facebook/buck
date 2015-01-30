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

package com.facebook.buck.thrift;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.python.PythonLibrary;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonUtil;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.nio.file.Path;

public class ThriftPythonEnhancer implements ThriftLanguageSpecificEnhancer {

  private static final Flavor PYTHON_FLAVOR = ImmutableFlavor.of("py");
  private static final Flavor PYTHON_TWISTED_FLAVOR = ImmutableFlavor.of("py-twisted");

  public static enum Type {
    NORMAL,
    TWISTED,
  }

  private final ThriftBuckConfig thriftBuckConfig;
  private final Type type;

  public ThriftPythonEnhancer(ThriftBuckConfig thriftBuckConfig, Type type) {
    this.thriftBuckConfig = thriftBuckConfig;
    this.type = type;
  }

  @Override
  public String getLanguage() {
    return "py";
  }

  @Override
  public Flavor getFlavor() {
    return type == Type.TWISTED ? PYTHON_TWISTED_FLAVOR : PYTHON_FLAVOR;
  }

  private ImmutableList<String> getGeneratedThriftSources(ImmutableList<String> services) {
    ImmutableList.Builder<String> sources = ImmutableList.builder();

    sources.add("constants.py");
    sources.add("ttypes.py");

    for (String service : services) {
      sources.add(service + ".py");
    }

    return sources.build();
  }

  @Override
  public PythonLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources,
      ImmutableSortedSet<BuildRule> deps) {

    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.pyBaseModule);

    ImmutableMap.Builder<Path, SourcePath> modulesBuilder = ImmutableMap.builder();

    // Iterate over all the thrift source, finding the python modules they generate and
    // building up a map of them.
    for (ImmutableMap.Entry<String, ThriftSource> ent : sources.entrySet()) {
      String thriftBaseName = Files.getNameWithoutExtension(ent.getKey());
      ThriftSource source = ent.getValue();
      Path outputDir = source.getOutputDir();

      for (String partialName : getGeneratedThriftSources(source.getServices())) {
        Path module = baseModule.resolve(thriftBaseName).resolve(partialName);
        Path path = outputDir
            .resolve("gen-" + getLanguage())
            .resolve(module);
        modulesBuilder.put(
            module,
            new BuildTargetSourcePath(source.getCompileRule().getBuildTarget(), path));
      }

    }

    ImmutableMap<Path, SourcePath> modules = modulesBuilder.build();

    // Create params which only use the language specific deps.
    BuildRuleParams langParams = params.copyWithChanges(
        PythonLibraryDescription.TYPE,
        params.getBuildTarget(),
        Suppliers.ofInstance(deps),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    // Construct a python library and return it as our language specific build rule.  Dependents
    // will use this to pull the generated sources into packages/PEXs.
    return new PythonLibrary(
        langParams,
        new SourcePathResolver(resolver),
        modules,
        ImmutableMap.<Path, SourcePath>of());
  }

  private ImmutableSet<BuildTarget> getImplicitDeps() {
    ImmutableSet.Builder<BuildTarget> implicitDeps = ImmutableSet.builder();

    implicitDeps.add(thriftBuckConfig.getPythonDep());

    if (type == Type.TWISTED) {
      implicitDeps.add(thriftBuckConfig.getPythonTwistedDep());
    }

    return implicitDeps.build();
  }

  @Override
  public ImmutableSet<BuildTarget> getImplicitDepsForTargetFromConstructorArg(
      BuildTarget target,
      ThriftConstructorArg arg) {
    return getImplicitDeps();
  }

  @Override
  public ImmutableSet<String> getOptions(
      BuildTarget target,
      ThriftConstructorArg args) {

    ImmutableSet.Builder<String> options = ImmutableSet.builder();

    if (args.pyOptions.isPresent()) {

      // Don't allow the "twisted" parameter to be pass via the options parameter, as
      // use a dedicated flavor to handle it.
      if (args.pyOptions.get().contains("twisted")) {
        throw new HumanReadableException(
            "%s: parameter \"py_options\": cannot specify \"twisted\" as an option" +
                " -- use the \"#%s\" flavor instead",
            target,
            PYTHON_TWISTED_FLAVOR);
      }

      options.addAll(args.pyOptions.get());
    }

    if (type == Type.TWISTED) {
      options.add("twisted");
    }

    return options.build();
  }

}
