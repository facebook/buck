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

package com.facebook.buck.core.build.context;

import static org.easymock.EasyMock.createMock;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.util.config.ConfigBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

/**
 * Facilitates creating a fake {@link com.facebook.buck.core.build.context.BuildContext} for unit
 * tests.
 */
public class FakeBuildContext {

  /** Utility class: do not instantiate. */
  private FakeBuildContext() {}

  public static final Tool NOOP_TOOL =
      new Tool() {
        @Override
        public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
          return ImmutableList.of();
        }

        @Override
        public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
          return ImmutableMap.of();
        }
      };

  /** A BuildContext which doesn't touch the host filesystem or actually execute steps. */
  public static final BuildContext NOOP_CONTEXT =
      withSourcePathResolver(createMock(SourcePathResolverAdapter.class));

  public static BuildContext withSourcePathResolver(SourcePathResolverAdapter pathResolver) {

    AbsPath rootPath = new FakeProjectFilesystem().getRootPath();
    Path path = rootPath.getPath();
    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(rootPath, ConfigBuilder.createFromText(""));

    return BuildContext.of(
        pathResolver,
        path,
        new FakeJavaPackageFinder(),
        BuckEventBusForTests.newInstance(),
        false,
        cellPathResolver,
        false,
        NOOP_TOOL);
  }

  /**
   * Same as {@link #withSourcePathResolver(SourcePathResolverAdapter)}, except that the returned
   * context uses the given filesystem's root path as the build cell root path.
   */
  public static BuildContext withSourcePathResolver(
      SourcePathResolverAdapter pathResolver, ProjectFilesystem filesystem) {

    AbsPath rootPath = filesystem.getRootPath();
    Path path = rootPath.getPath();
    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(rootPath, ConfigBuilder.createFromText(""));

    return BuildContext.of(
        pathResolver,
        path,
        new FakeJavaPackageFinder(),
        BuckEventBusForTests.newInstance(),
        false,
        cellPathResolver,
        false,
        NOOP_TOOL);
  }

  public static BuildContext create(
      SourcePathResolverAdapter pathResolver, BuckEventBus buckEventBus) {

    AbsPath rootPath = new FakeProjectFilesystem().getRootPath();
    Path path = rootPath.getPath();
    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(rootPath, ConfigBuilder.createFromText(""));

    return BuildContext.of(
        pathResolver,
        path,
        new FakeJavaPackageFinder(),
        buckEventBus,
        false,
        cellPathResolver,
        false,
        NOOP_TOOL);
  }
}
