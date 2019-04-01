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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.ElfSharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceFactory;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfSharedLibraryInterfaceFactory implements SharedLibraryInterfaceFactory {

  abstract ToolProvider getObjcopy();

  abstract boolean isRemoveUndefinedSymbols();

  @Override
  public final BuildRule createSharedInterfaceLibraryFromLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      SourcePath library) {
    return ElfSharedLibraryInterface.from(
        target,
        projectFilesystem,
        pathResolver,
        ruleFinder,
        getObjcopy().resolve(resolver, target.getTargetConfiguration()),
        library,
        isRemoveUndefinedSymbols());
  }

  @Override
  public final BuildRule createSharedInterfaceLibraryFromLinkableInput(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      String libName,
      Linker linker,
      ImmutableList<Arg> args) {
    return ElfSharedLibraryInterface.from(
        target,
        projectFilesystem,
        ruleFinder,
        getObjcopy().resolve(resolver, target.getTargetConfiguration()),
        libName,
        linker,
        args,
        isRemoveUndefinedSymbols());
  }

  public static ElfSharedLibraryInterfaceFactory from(ElfSharedLibraryInterfaceParams params) {
    return ElfSharedLibraryInterfaceFactory.of(
        params.getObjcopy(), params.isRemoveUndefinedSymbols());
  }
}
