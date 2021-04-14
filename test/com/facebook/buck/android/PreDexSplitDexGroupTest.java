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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.ZipSplitter;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PreDexSplitDexGroupTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private APKModuleGraph moduleGraph;

  @Before
  public void setUp() {
    moduleGraph =
        new APKModuleGraph(
            TargetGraph.EMPTY,
            BuildTargetFactory.newInstance("//fakeTarget:yes"),
            Optional.empty());
  }

  @Test
  public void testWritesPrimaryDexClassNames() throws IOException, InterruptedException {
    runTestWritesPrimaryDexClassNames(true);
  }

  @Test
  public void testDoesNotWritePrimaryDexClassNames() throws IOException, InterruptedException {
    runTestWritesPrimaryDexClassNames(false);
  }

  private void runTestWritesPrimaryDexClassNames(boolean writesNames)
      throws IOException, InterruptedException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    BuildTarget preDexSplitDexTarget =
        BuildTargetFactory.newInstance("//java/com/example:example#pre_dex_group");

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    JavaLibrary javaLibrary = new FakeJavaLibrary(javaLibraryTarget);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:lib#d8");
    DexProducedFromJavaLibrary dexFromJavaLibrary =
        new DexProducedFromJavaLibrary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            TestAndroidPlatformTargetFactory.create(),
            javaLibrary,
            false);
    graphBuilder.addToIndex(dexFromJavaLibrary);
    dexFromJavaLibrary
        .getBuildOutputInitializer()
        .setBuildOutputForTests(
            new DexProducedFromJavaLibrary.BuildOutput(
                /* weightEstimate */ 1600,
                /* classNamesToHashes */ ImmutableSortedMap.of(
                    "com/example/Primary", HashCode.fromString(Strings.repeat("abcdabcd", 5))),
                ImmutableList.of()));

    PreDexSplitDexGroup preDexSplitDexGroup =
        new PreDexSplitDexGroup(
            preDexSplitDexTarget,
            projectFilesystem,
            TestBuildRuleParams.create(),
            AndroidTestUtils.createAndroidPlatformTarget(),
            D8Step.D8,
            new DexSplitMode(
                /* shouldSplitDex */ true,
                ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
                DexStore.JAR,
                /* linearAllocHardLimit */ 4 * 1024 * 1024,
                /* primaryDexPatterns */ ImmutableSet.of("Primary"),
                Optional.of(FakeSourcePath.of("the/manifest.txt")),
                /* primaryDexScenarioFile */ Optional.empty(),
                /* isPrimaryDexScenarioOverflowAllowed */ false,
                /* secondaryDexHeadClassesFile */ Optional.empty(),
                /* secondaryDexTailClassesFile */ Optional.empty(),
                /* allowRDotJavaInSecondaryDex */ false),
            moduleGraph,
            moduleGraph.getRootAPKModule(),
            ImmutableList.of(dexFromJavaLibrary),
            MoreExecutors.newDirectExecutorService(),
            0,
            Optional.empty(),
            10000,
            writesNames);

    ImmutableList<Step> buildSteps =
        preDexSplitDexGroup.getBuildSteps(buildContext, new FakeBuildableContext());

    List<Step> writePrimaryDexStep =
        buildSteps.stream()
            .filter(step -> step.getShortName().equals("maybe_write_primary_dex_class_names"))
            .collect(Collectors.toList());
    assertEquals(1, writePrimaryDexStep.size());

    writePrimaryDexStep.get(0).execute(TestExecutionContext.newInstance());

    Optional<String> primaryDexFile =
        projectFilesystem.readFileIfItExists(preDexSplitDexGroup.getPrimaryDexClassNamesPath());
    assertTrue(primaryDexFile.isPresent());
    if (writesNames) {
      assertTrue(primaryDexFile.get().contains("com/example/Primary"));
    } else {
      assertFalse(primaryDexFile.get().contains("com/example/Primary"));
    }
  }
}
