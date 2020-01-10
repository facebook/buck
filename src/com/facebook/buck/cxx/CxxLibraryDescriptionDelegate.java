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
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Defines a plugin interface for {@link com.facebook.buck.cxx.CxxLibraryDescription} so that its
 * behavior can be extended. This is usually useful for description that use {@link
 * com.facebook.buck.cxx.CxxLibraryDescription} as a delegate (e.g., {@link
 * com.facebook.buck.apple.AppleLibraryDescription}.
 */
public interface CxxLibraryDescriptionDelegate {

  /** The delegate for a specific target and platform. */
  interface ConfiguredDelegate {
    /**
     * Defines an additional preprocessor input for the public interface exposed by a target. The
     * returned input will be concatenated with {@link
     * com.facebook.buck.cxx.CxxLibraryDescription}'s public input.
     */
    Optional<CxxPreprocessorInput> getPreprocessorInput();
    /**
     * Defines an additional preprocessor input for the private interface exposed by a target. The
     * returned input will be concatenated with {@link
     * com.facebook.buck.cxx.CxxLibraryDescription}'s private input.
     */
    Optional<CxxPreprocessorInput> getPrivatePreprocessorInput();

    /**
     * Defines an additional private {@link HeaderSymlinkTree} that will be used when compiling the
     * the library.
     */
    Optional<HeaderSymlinkTree> getPrivateHeaderSymlinkTree();

    /**
     * Defines the paths to object files (i.e., .o files) that will be combined into the final
     * product of {@link com.facebook.buck.cxx.CxxLibraryDescription}. If the paths depend on build
     * rules, you must use {@link ExplicitBuildTargetSourcePath} to make sure the build rule deps
     * are correctly set up.
     */
    ImmutableList<SourcePath> getObjectFilePaths();

    /**
     * Provides the ability for the plugin to provide additional {@link NativeLinkable}s that will
     * be exported.
     */
    Optional<ImmutableList<NativeLinkableGroup>> getNativeLinkableExportedDeps();

    /** Provides the ability to inject additional exported linker flags. */
    ImmutableList<Arg> getAdditionalExportedLinkerFlags();

    /**
     * Specifies whether a library artifact (e.g., libName.a) should be produced. For example,
     * header-only libs will not normally produce a library. Since {@link CxxLibraryDescription} is
     * not aware of other sources, it uses this method as an additional signal to determine whether
     * it should produce a final artifact, even it doesn't have to if looking at just its own
     * sources.
     */
    boolean getShouldProduceLibraryArtifact();
  }

  Optional<ConfiguredDelegate> requireDelegate(
      BuildTarget target, CxxPlatform platform, ActionGraphBuilder graphBuilder);

  static CxxLibraryDescriptionDelegate noop() {
    return (target, platform1, graphBuilder1) -> Optional.empty();
  }
}
