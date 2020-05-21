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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.PBXObjectGIDFactory;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.graph.AbstractBottomUpTraversal;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Test helper class configuring `buck project` for Apple development. */
public class AppleProjectHelper {

  static final Path OUTPUT_DIRECTORY = Paths.get("_gen");
  static final String PROJECT_NAME = "GeneratedProject";

  static Path getBuildScriptPath(ProjectFilesystem filesystem) throws IOException {
    Path path = Paths.get("build_script.sh");
    filesystem.touch(path);
    return path;
  }

  static BuckConfig createDefaultBuckConfig(ProjectFilesystem filesystem) throws IOException {
    Path path = getBuildScriptPath(filesystem);

    return FakeBuckConfig.builder()
        .setSections(
            ImmutableMap.of(
                AppleConfig.APPLE_SECTION,
                ImmutableMap.of(AppleConfig.BUILD_SCRIPT, path.toString())))
        .setFilesystem(filesystem)
        .build();
  }

  static AppleConfig createDefaultAppleConfig(ProjectFilesystem filesystem) throws IOException {
    BuckConfig buckConfig = createDefaultBuckConfig(filesystem);
    return buckConfig.getView(AppleConfig.class);
  }

  static XcodeProjectWriteOptions defaultXcodeProjectWriteOptions() {
    return XcodeProjectWriteOptions.of(
        new PBXProject(PROJECT_NAME, Optional.empty(), AbstractPBXObjectFactory.DefaultFactory()),
        new PBXObjectGIDFactory(),
        OUTPUT_DIRECTORY);
  }

  static PathRelativizer defaultPathRelativizer(String output_path) {
    return new PathRelativizer(
        Paths.get(output_path),
        new TestActionGraphBuilder().getSourcePathResolver()::getRelativePath);
  }

  static SourcePathResolverAdapter defaultSourcePathResolverAdapter(
      BuildRuleResolver buildRuleResolver) {
    return buildRuleResolver.getSourcePathResolver();
  }

  static ActionGraphBuilder getActionGraphBuilderNodeFunction(TargetGraph targetGraph) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    AbstractBottomUpTraversal<TargetNode<?>, RuntimeException> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(targetGraph) {
          @Override
          @SuppressWarnings("PMD.EmptyCatchBlock")
          public void visit(TargetNode<?> node) {
            try {
              graphBuilder.requireRule(node.getBuildTarget());
            } catch (Exception e) {
              // NOTE(agallagher): A large number of the tests appear to setup their target nodes
              // incorrectly, causing action graph creation to fail with lots of missing expected
              // Apple C/C++ platform flavors.  This is gross, but to support tests that need a
              // complete sub-action graph, just skip over the errors.
            }
          }
        };
    bottomUpTraversal.traverse();
    return graphBuilder;
  }
}
