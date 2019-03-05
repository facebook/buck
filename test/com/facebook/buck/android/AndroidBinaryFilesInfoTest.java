/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AndroidBinaryFilesInfoTest {

  private AndroidBinaryFilesInfo androidBinaryFilesInfo;
  private FakePreDexMerge preDexMerge;

  @Before
  public void setUp() {
    EnumSet<ExopackageMode> exopackageModes = EnumSet.of(ExopackageMode.MODULES);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//app:app");
    APKModuleGraph apkModuleGraph =
        new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty());
    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(apkTarget, ImmutableSet.of(), apkModuleGraph).build();

    preDexMerge = new FakePreDexMerge(apkTarget, apkModuleGraph);
    preDexMerge.dexInfo =
        new DexFilesInfo(
            FakeSourcePath.of("primary.dex"),
            ImmutableSortedSet.of(FakeSourcePath.of("secondary_dexes")),
            Optional.empty(),
            ImmutableMap.of());
    AndroidGraphEnhancementResult enhancementResult =
        AndroidGraphEnhancementResult.builder()
            .setDexMergeRule(Either.ofLeft(preDexMerge))
            .setPackageableCollection(collection)
            .setPrimaryResourcesApkPath(FakeSourcePath.of("primary_resources.apk"))
            .setAndroidManifestPath(FakeSourcePath.of("AndroidManifest.xml"))
            .setAPKModuleGraph(apkModuleGraph)
            .build();
    androidBinaryFilesInfo = new AndroidBinaryFilesInfo(enhancementResult, exopackageModes, false);
  }

  @Test
  public void getExopackageInfo() {
    Pair<SourcePath, SourcePath> metadataAndSourcePath =
        new Pair<>(
            FakeSourcePath.of(Paths.get("module_name", "metadata.txt")),
            FakeSourcePath.of(Paths.get("module_name")));
    preDexMerge.moduleMetadataAndDexSources = ImmutableList.of(metadataAndSourcePath);

    ExopackageInfo info = androidBinaryFilesInfo.getExopackageInfo().get();
    ImmutableList<DexInfo> moduleInfo = info.getModuleInfo().get();
    Assert.assertThat(moduleInfo, Matchers.hasSize(1));
    DexInfo dexInfo = moduleInfo.get(0);
    Assert.assertEquals(metadataAndSourcePath.getFirst(), dexInfo.getMetadata());
    Assert.assertEquals(metadataAndSourcePath.getSecond(), dexInfo.getDirectory());
  }

  private class FakePreDexMerge extends PreDexMerge {
    DexFilesInfo dexInfo;
    List<Pair<SourcePath, SourcePath>> moduleMetadataAndDexSources;

    FakePreDexMerge(BuildTarget buildTarget, APKModuleGraph apkModuleGraph) {
      super(
          buildTarget,
          new FakeProjectFilesystem(),
          null,
          new BuildRuleParams(
              ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of()),
          new DexSplitMode(
              /* shouldSplitDex */ true,
              DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
              DexStore.JAR,
              /* linearAllocHardLimit */ 4 * 1024 * 1024,
              /* primaryDexPatterns */ ImmutableSet.of("List"),
              Optional.of(FakeSourcePath.of("the/manifest.txt")),
              /* primaryDexScenarioFile */ Optional.empty(),
              /* isPrimaryDexScenarioOverflowAllowed */ false,
              /* secondaryDexHeadClassesFile */ Optional.empty(),
              /* secondaryDexTailClassesFile */ Optional.empty(),
              /* allowRDotJavaInSecondaryDex */ false),
          apkModuleGraph,
          null,
          MoreExecutors.newDirectExecutorService(),
          OptionalInt.empty(),
          Optional.empty(),
          "dx");
    }

    @Override
    public DexFilesInfo getDexFilesInfo() {
      return dexInfo;
    }

    @Override
    public Stream<Pair<SourcePath, SourcePath>> getModuleMetadataAndDexSourcePaths() {
      return moduleMetadataAndDexSources.stream();
    }
  }
}
