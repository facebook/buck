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

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class TrimUberRDotJavaTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Test
  public void testTrimming() throws IOException, InterruptedException {
    Optional<String> keepResourcePattern = Optional.empty();
    String rDotJavaContentsAfterFiltering =
        "package com.test;\n"
            + "\n"
            + "public class R {\n"
            + "  public static class string {\n"
            + "    public static final int my_first_resource=0x7f08005c;\n"
            + "  }\n"
            + "}\n";
    doTrimingTest(keepResourcePattern, rDotJavaContentsAfterFiltering);
  }

  @Test
  public void testTrimmingWithKeepPattern() throws IOException, InterruptedException {
    Optional<String> keepResourcePattern = Optional.of("^keep_resource.*");
    String rDotJavaContentsAfterFiltering =
        "package com.test;\n"
            + "\n"
            + "public class R {\n"
            + "  public static class string {\n"
            + "    public static final int my_first_resource=0x7f08005c;\n"
            + "    public static final int keep_resource=0x7f083bc2;\n"
            + "  }\n"
            + "}\n";
    doTrimingTest(keepResourcePattern, rDotJavaContentsAfterFiltering);
  }

  private void doTrimingTest(
      Optional<String> keepResourcePattern, String rDotJavaContentsAfterFiltering)
      throws InterruptedException, IOException {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    String rDotJavaContents =
        "package com.test;\n"
            + "\n"
            + "public class R {\n"
            + "  public static class string {\n"
            + "    public static final int my_first_resource=0x7f08005c;\n"
            + "    public static final int my_second_resource=0x7f083bc1;\n"
            + "    public static final int keep_resource=0x7f083bc2;\n"
            + "  }\n"
            + "}\n";
    Path rDotJavaDir =
        BuildTargets.getGenPath(
            filesystem,
            BuildTargetFactory.newInstance("//:aapt#aapt_package_resources"),
            "%s/__r_java_srcs__/R.java");
    Path rDotJavaPath = rDotJavaDir.resolve("com/test/R.java");
    filesystem.createParentDirs(rDotJavaPath);
    filesystem.writeContentsToPath(rDotJavaContents, rDotJavaPath);

    BuildTarget dexTarget = BuildTargetFactory.newInstance("//:dex");
    DexProducedFromJavaLibrary dexProducedFromJavaLibrary =
        new DexProducedFromJavaLibrary(
            dexTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            TestBuildRuleParams.create(),
            new FakeJavaLibrary(BuildTargetFactory.newInstance("//:lib"), null));
    dexProducedFromJavaLibrary
        .getBuildOutputInitializer()
        .setBuildOutputForTests(
            new DexProducedFromJavaLibrary.BuildOutput(
                1,
                ImmutableSortedMap.of(),
                Optional.of(ImmutableList.of("com.test.my_first_resource"))));

    graphBuilder.addToIndex(dexProducedFromJavaLibrary);

    BuildTarget trimTarget = BuildTargetFactory.newInstance("//:trim");
    TrimUberRDotJava trimUberRDotJava =
        new TrimUberRDotJava(
            trimTarget,
            filesystem,
            TestBuildRuleParams.create(),
            Optional.of(FakeSourcePath.of(filesystem, rDotJavaDir)),
            ImmutableList.of(dexProducedFromJavaLibrary),
            keepResourcePattern);
    graphBuilder.addToIndex(trimUberRDotJava);

    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildableContext buildableContext = new FakeBuildableContext();
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setCellPathResolver(TestCellPathResolver.get(filesystem))
            .build();
    ImmutableList<Step> steps = trimUberRDotJava.getBuildSteps(buildContext, buildableContext);
    for (Step step : steps) {
      step.execute(executionContext);
    }

    ZipInspector inspector =
        new ZipInspector(pathResolver.getAbsolutePath(trimUberRDotJava.getSourcePathToOutput()));
    inspector.assertFileContents("com/test/R.java", rDotJavaContentsAfterFiltering);
  }
}
