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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Prebuilt OCaml library */
public class PrebuiltOcamlLibraryDescription
    implements Description<PrebuiltOcamlLibraryDescriptionArg>,
        VersionPropagator<PrebuiltOcamlLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltOcamlLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltOcamlLibraryDescriptionArg.class;
  }

  @Override
  public OcamlLibrary createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltOcamlLibraryDescriptionArg args) {

    boolean bytecodeOnly = args.getBytecodeOnly();

    String libDir = args.getLibDir();

    String nativeLib = args.getNativeLib();
    String bytecodeLib = args.getBytecodeLib();
    ImmutableList<String> cLibs = args.getCLibs();
    ImmutableList<String> nativeCLibs = args.getNativeCLibs();
    ImmutableList<String> bytecodeCLibs = args.getBytecodeCLibs();

    Path libPath = buildTarget.getBasePath().resolve(libDir);
    Path includeDir = libPath.resolve(args.getIncludeDir());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Optional<SourcePath> staticNativeLibraryPath =
        bytecodeOnly
            ? Optional.empty()
            : Optional.of(PathSourcePath.of(projectFilesystem, libPath.resolve(nativeLib)));
    SourcePath staticBytecodeLibraryPath =
        PathSourcePath.of(projectFilesystem, libPath.resolve(bytecodeLib));
    ImmutableList<SourcePath> staticCLibraryPaths =
        cLibs
            .stream()
            .map(input -> PathSourcePath.of(projectFilesystem, libPath.resolve(input)))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<SourcePath> staticNativeCLibraryPaths =
        nativeCLibs
            .stream()
            .map(input -> PathSourcePath.of(projectFilesystem, libPath.resolve(input)))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<SourcePath> staticBytecodeCLibraryPaths =
        bytecodeCLibs
            .stream()
            .map(input -> PathSourcePath.of(projectFilesystem, libPath.resolve(input)))
            .collect(ImmutableList.toImmutableList());

    SourcePath bytecodeLibraryPath =
        PathSourcePath.of(projectFilesystem, libPath.resolve(bytecodeLib));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getBuildRuleResolver());

    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    return new PrebuiltOcamlLibrary(
        buildTarget,
        projectFilesystem,
        params,
        ruleFinder,
        staticNativeLibraryPath,
        staticBytecodeLibraryPath,
        staticCLibraryPaths,
        staticNativeCLibraryPaths,
        staticBytecodeCLibraryPaths,
        bytecodeLibraryPath,
        libPath,
        includeDir,
        allDeps);
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractPrebuiltOcamlLibraryDescriptionArg
      implements CommonDescriptionArg, HasDeclaredDeps {

    @Value.Default
    String getLibDir() {
      return "lib";
    }

    @Value.Default
    String getIncludeDir() {
      return "";
    }

    @Value.Default
    String getLibName() {
      return getName();
    }

    @Value.Default
    String getNativeLib() {
      return String.format("%s.cmxa", getLibName());
    }

    @Value.Default
    String getBytecodeLib() {
      return String.format("%s.cma", getLibName());
    }

    abstract ImmutableList<String> getCLibs();

    abstract ImmutableList<String> getNativeCLibs();

    abstract ImmutableList<String> getBytecodeCLibs();

    @Value.Default
    boolean getBytecodeOnly() {
      return false;
    }

    @Value.Default
    PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
