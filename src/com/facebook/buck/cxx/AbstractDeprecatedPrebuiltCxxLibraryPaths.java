/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDeprecatedPrebuiltCxxLibraryPaths implements PrebuiltCxxLibraryPaths {

  abstract BuildTarget getTarget();

  abstract Optional<String> getVersionSubdir();

  abstract ImmutableList<String> getIncludeDirs();

  abstract Optional<String> getLibDir();

  abstract Optional<String> getLibName();

  // Platform unlike most macro expanders needs access to the cxx build flavor.
  // Because of that it can't be like normal expanders. So just create a handler here.
  private static MacroHandler getMacroHandler(Optional<CxxPlatform> cxxPlatform) {
    String flav = cxxPlatform.map(input -> input.getFlavor().toString()).orElse("");
    return new MacroHandler(
        ImmutableMap.of(
            "location",
            cxxPlatform
                .<LocationMacroExpander>map(CxxLocationMacroExpander::new)
                .orElseGet(LocationMacroExpander::new),
            "platform",
            new StringExpander<>(Macro.class, StringArg.of(flav))));
  }

  private String expandMacros(
      BuildRuleResolver resolver, CellPathResolver cellRoots, CxxPlatform cxxPlatform, String str) {
    try {
      return getMacroHandler(Optional.of(cxxPlatform))
          .expand(getTarget(), cellRoots, resolver, str);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s in \"%s\"", getTarget(), e.getMessage(), str);
    }
  }

  private SourcePath getSourcePath(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      String subPath,
      Optional<String> suffix) {

    subPath = expandMacros(resolver, cellRoots, cxxPlatform, subPath);
    suffix = suffix.map(s -> expandMacros(resolver, cellRoots, cxxPlatform, s));

    // Check if we have a location macro that wraps a target.
    if (getLibDir().isPresent()) {
      Optional<BuildRule> dep;
      try {
        dep =
            getMacroHandler(Optional.of(cxxPlatform))
                .extractBuildTimeDeps(getTarget(), cellRoots, resolver, getLibDir().orElse("lib"))
                .stream()
                .findAny();
      } catch (MacroException e) {
        dep = Optional.empty();
      }

      // If we get here then this is referencing the output from a build rule.
      // This always return a ExplicitBuildTargetSourcePath
      if (dep.isPresent()) {
        Path path = filesystem.resolve(subPath);
        path = suffix.map(path::resolve).orElse(path);
        path = filesystem.relativize(path);
        return ExplicitBuildTargetSourcePath.of(dep.get().getBuildTarget(), path);
      }
    }

    // If there are no deps then this is just referencing a path that should already be there
    // So just expand the macros and return a PathSourcePath
    Path path = getTarget().getBasePath();
    path = getVersionSubdir().map(path::resolve).orElse(path);
    path = path.resolve(subPath);
    path = suffix.map(path::resolve).orElse(path);
    return PathSourcePath.of(filesystem, path);
  }

  private Optional<SourcePath> getLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      LibType type) {
    SourcePath sourcePath =
        getSourcePath(
            filesystem,
            resolver,
            cellRoots,
            cxxPlatform,
            getLibDir().orElse("lib"),
            Optional.of(
                String.format(
                    "lib%s%s",
                    getLibName().orElse(getTarget().getShortName()), type.getSuffix(cxxPlatform))));

    // TODO(agallagher): Touching the FS is bad! Remove once all users have transitioned to the new
    // API.
    if (sourcePath instanceof PathSourcePath) {
      PathSourcePath path = (PathSourcePath) sourcePath;
      if (!path.getFilesystem().exists(path.getRelativePath())) {
        return Optional.empty();
      }
    }

    return Optional.of(sourcePath);
  }

  @Override
  public Optional<SourcePath> getSharedLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(filesystem, resolver, cellRoots, cxxPlatform, LibType.SHARED);
  }

  @Override
  public Optional<SourcePath> getStaticLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(filesystem, resolver, cellRoots, cxxPlatform, LibType.STATIC);
  }

  @Override
  public Optional<SourcePath> getStaticPicLibrary(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return getLibrary(filesystem, resolver, cellRoots, cxxPlatform, LibType.STATIC_PIC);
  }

  @Override
  public ImmutableList<SourcePath> getIncludeDirs(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return RichStream.from(getIncludeDirs())
        .map(
            dir ->
                getSourcePath(filesystem, resolver, cellRoots, cxxPlatform, dir, Optional.empty()))
        .toImmutableList();
  }

  @Override
  public Optional<NativeLinkable.Linkage> getLinkage(
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform) {
    Optional<SourcePath> staticLibrary =
        getLibrary(filesystem, resolver, cellRoots, cxxPlatform, LibType.STATIC);
    Optional<SourcePath> staticPicLibrary =
        getLibrary(filesystem, resolver, cellRoots, cxxPlatform, LibType.STATIC_PIC);
    if (!staticLibrary.isPresent() && !staticPicLibrary.isPresent()) {
      return Optional.of(NativeLinkable.Linkage.SHARED);
    }
    return Optional.empty();
  }

  @Override
  public void findParseTimeDeps(
      CellPathResolver cellRoots,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    RichStream.from(getIncludeDirs())
        .concat(getLibDir().map(Stream::of).orElse(Stream.of()))
        .forEach(
            path -> {
              try {
                // doesn't matter that the platform expander doesn't do anything.
                MacroHandler macroHandler = getMacroHandler(Optional.empty());
                // Then get the parse time deps.
                macroHandler.extractParseTimeDeps(
                    getTarget(), cellRoots, path, extraDepsBuilder, targetGraphOnlyDepsBuilder);
              } catch (MacroException e) {
                throw new HumanReadableException(
                    e, "%s : %s in \"%s\"", getTarget(), e.getMessage(), path);
              }
            });
  }

  private enum LibType {
    SHARED {
      @Override
      public String getSuffix(CxxPlatform cxxPlatform) {
        return String.format(".%s", cxxPlatform.getSharedLibraryExtension());
      }
    },
    STATIC {
      @Override
      public String getSuffix(CxxPlatform cxxPlatform) {
        return String.format(".%s", cxxPlatform.getStaticLibraryExtension());
      }
    },
    STATIC_PIC {
      @Override
      public String getSuffix(CxxPlatform cxxPlatform) {
        return String.format("_pic.%s", cxxPlatform.getStaticLibraryExtension());
      }
    },
    ;

    public abstract String getSuffix(CxxPlatform cxxPlatform);
  }
}
