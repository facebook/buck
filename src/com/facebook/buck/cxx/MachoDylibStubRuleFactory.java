/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.MachoDylibStubParams;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceFactory;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;

/** Factor which can create rules for scrubbed dylib stubs */
@BuckStyleValue
public abstract class MachoDylibStubRuleFactory implements SharedLibraryInterfaceFactory {

  abstract Tool getStrip();

  @Override
  public BuildRule createSharedInterfaceLibraryFromLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      SourcePath library) {
    return MachoDylibStubRule.from(target, projectFilesystem, resolver, getStrip(), library);
  }

  @Override
  public BuildRule createSharedInterfaceLibraryFromLinkableInput(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      String libName,
      Linker linker,
      ImmutableList<Arg> args) {
    throw new RuntimeException("Unsupported for Mach-O");
  }

  public static MachoDylibStubRuleFactory from(MachoDylibStubParams params) {
    return ImmutableMachoDylibStubRuleFactory.of(params.getStrip());
  }
}
