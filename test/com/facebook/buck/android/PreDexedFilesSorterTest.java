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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PreDexedFilesSorterTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private static final String PRIMARY_DEX_PATTERN = "primary";
  private static final long DEX_WEIGHT_LIMIT = 10 * 1024 * 1024;
  private static final int STANDARD_DEX_FILE_ESTIMATE = (int) DEX_WEIGHT_LIMIT / 10 - 1;

  private APKModuleGraph moduleGraph;
  private APKModule extraModule;

  @Before
  public void setUp() {
    moduleGraph =
        new APKModuleGraph(
            TargetGraph.EMPTY,
            BuildTargetFactory.newInstance("//fakeTarget:yes"),
            Optional.empty());
    extraModule = APKModule.of("extra", false, false);
  }

  @Test
  public void testPrimaryOnly() throws IOException {
    int numberOfPrimaryDexes = 10;
    int numberOfSecondaryDexes = 0;
    int numberOfExtraDexes = 0;

    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        generatePreDexSorterResults(
            numberOfPrimaryDexes, numberOfSecondaryDexes, numberOfExtraDexes);
    for (String store : sortResults.keySet()) {
      assertThat(store, is(moduleGraph.getRootAPKModule().getName()));
    }
    assertThat(sortResults.size(), is(1));
  }

  @Test
  public void testPrimaryAndSecondary() throws IOException {
    int numberOfPrimaryDexes = 10;
    int numberOfSecondaryDexes = 10;
    int numberOfExtraDexes = 0;

    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        generatePreDexSorterResults(
            numberOfPrimaryDexes, numberOfSecondaryDexes, numberOfExtraDexes);
    PreDexedFilesSorter.Result rootResult = sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);
    for (String store : sortResults.keySet()) {
      assertThat(store, is(moduleGraph.getRootAPKModule().getName()));
    }
    assertThat(rootResult.primaryDexInputs.size(), is(numberOfPrimaryDexes));
    assertThat(rootResult.secondaryOutputToInputs.keySet().size(), is(1));
    assertThat(rootResult.secondaryOutputToInputs.size(), is(numberOfSecondaryDexes + 1));
  }

  @Test
  public void testPrimaryAndMultipleSecondary() throws IOException {
    int numberOfPrimaryDexes = 10;
    int numberOfSecondaryDexes = 15;
    int numberOfExtraDexes = 0;

    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        generatePreDexSorterResults(
            numberOfPrimaryDexes, numberOfSecondaryDexes, numberOfExtraDexes);
    for (String store : sortResults.keySet()) {
      assertThat(store, is(moduleGraph.getRootAPKModule().getName()));
    }
    PreDexedFilesSorter.Result rootResult = sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);

    assertThat(rootResult.metadataTxtDexEntries.size(), is(2));

    for (DexWithClasses dexWithClasses : rootResult.metadataTxtDexEntries.values()) {
      assertThat(dexWithClasses.getClassNames().asList().get(0), Matchers.endsWith("/Canary"));
    }

    assertThat(rootResult.primaryDexInputs.size(), is(numberOfPrimaryDexes));
    // check that we have 2 secondary stores
    assertThat(rootResult.secondaryOutputToInputs.keySet().size(), is(2));
    // check that we have 11 secondary inputs + 2 from canaries
    assertThat(rootResult.secondaryOutputToInputs.size(), is(numberOfSecondaryDexes + 2));
  }

  @Test
  public void testPrimaryAndExtraModule() throws IOException {
    int numberOfPrimaryDexes = 10;
    int numberOfSecondaryDexes = 0;
    int numberOfExtraDexes = 10;

    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        generatePreDexSorterResults(
            numberOfPrimaryDexes, numberOfSecondaryDexes, numberOfExtraDexes);
    for (String store : sortResults.keySet()) {
      assertThat(store, oneOf(moduleGraph.getRootAPKModule().getName(), extraModule.getName()));
    }
    assertThat(sortResults.size(), is(2));

    PreDexedFilesSorter.Result rootResult = sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);
    assertThat(rootResult.primaryDexInputs.size(), is(numberOfPrimaryDexes));

    PreDexedFilesSorter.Result extraResult = sortResults.get(extraModule.getName());
    assertThat(extraResult.metadataTxtDexEntries.size(), is(1));
    assertThat(extraResult.secondaryOutputToInputs.size(), is(numberOfExtraDexes + 1));
    for (DexWithClasses dexWithClasses : extraResult.metadataTxtDexEntries.values()) {
      assertThat(dexWithClasses.getClassNames().asList().get(0), Matchers.endsWith("/Canary"));
    }
  }

  @Test
  public void testPrimarySecondaryAndExtraModule() throws IOException {
    int numberOfPrimaryDexes = 10;
    int numberOfSecondaryDexes = 15;
    int numberOfExtraDexes = 15;

    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        generatePreDexSorterResults(
            numberOfPrimaryDexes, numberOfSecondaryDexes, numberOfExtraDexes);
    for (String store : sortResults.keySet()) {
      assertThat(store, oneOf(moduleGraph.getRootAPKModule().getName(), extraModule.getName()));
    }
    PreDexedFilesSorter.Result rootResult = sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);
    PreDexedFilesSorter.Result extraResult = sortResults.get(extraModule.getName());

    assertThat(sortResults.size(), is(2));
    assertThat(rootResult.primaryDexInputs.size(), is(numberOfPrimaryDexes));
    assertThat(rootResult.metadataTxtDexEntries.size(), is(2));
    assertThat(extraResult.metadataTxtDexEntries.size(), is(2));

    for (DexWithClasses dexWithClasses : rootResult.metadataTxtDexEntries.values()) {
      assertThat(dexWithClasses.getClassNames().asList().get(0), Matchers.endsWith("/Canary"));
    }
    for (DexWithClasses dexWithClasses : extraResult.metadataTxtDexEntries.values()) {
      assertThat(dexWithClasses.getClassNames().asList().get(0), Matchers.endsWith("/Canary"));
    }
  }

  private ImmutableMap<String, PreDexedFilesSorter.Result> generatePreDexSorterResults(
      int numberOfPrimaryDexes, int numberOfSecondaryDexes, int numberOfExtraDexes)
      throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMultimap.Builder<APKModule, DexWithClasses> inputDexes = ImmutableMultimap.builder();
    for (int i = 0; i < numberOfPrimaryDexes; i++) {
      inputDexes.put(
          moduleGraph.getRootAPKModule(),
          createFakeDexWithClasses(
              filesystem,
              Paths.get("primary").resolve(String.format("primary%d.dex", i)),
              ImmutableSet.of(String.format("primary.primary%d.class", i)),
              STANDARD_DEX_FILE_ESTIMATE));
    }
    for (int i = 0; i < numberOfSecondaryDexes; i++) {
      inputDexes.put(
          moduleGraph.getRootAPKModule(),
          createFakeDexWithClasses(
              filesystem,
              Paths.get("secondary").resolve(String.format("secondary%d.dex", i)),
              ImmutableSet.of(String.format("secondary.secondary%d.class", i)),
              STANDARD_DEX_FILE_ESTIMATE));
    }
    for (int i = 0; i < numberOfExtraDexes; i++) {
      inputDexes.put(
          extraModule,
          createFakeDexWithClasses(
              filesystem,
              Paths.get("extra").resolve(String.format("extra%d.dex", i)),
              ImmutableSet.of(String.format("extra.extra%d.class", i)),
              STANDARD_DEX_FILE_ESTIMATE));
    }

    ImmutableMultimap<APKModule, DexWithClasses> dexes = inputDexes.build();
    ImmutableMap.Builder<String, PreDexedFilesSorter.Result> results = ImmutableMap.builder();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    for (APKModule module : dexes.keySet()) {
      PreDexedFilesSorter sorter =
          new PreDexedFilesSorter(
              dexes.get(module),
              ImmutableSet.of(PRIMARY_DEX_PATTERN),
              moduleGraph,
              module,
              tempDir.newFolder(module.getName(), "scratch").toPath(),
              DEX_WEIGHT_LIMIT,
              DexStore.JAR,
              tempDir.newFolder(module.getName(), "secondary").toPath(),
              Optional.empty());
      results.put(module.getName(), sorter.sortIntoPrimaryAndSecondaryDexes(filesystem, steps));
    }
    return results.build();
  }

  private DexWithClasses createFakeDexWithClasses(
      ProjectFilesystem filesystem,
      Path pathToDex,
      ImmutableSet<String> classNames,
      int weightEstimate) {
    return new DexWithClasses() {
      @Override
      public BuildTarget getSourceBuildTarget() {
        return BuildTargetFactory.newInstance(
            pathToDex.getParent() + ":" + pathToDex.getFileName());
      }

      @Override
      public SourcePath getSourcePathToDexFile() {
        return PathSourcePath.of(filesystem, pathToDex);
      }

      @Override
      public ImmutableSet<String> getClassNames() {
        return classNames;
      }

      @Override
      public Sha1HashCode getClassesHash() {
        return Sha1HashCode.of(String.format("%040x", classNames.hashCode()));
      }

      @Override
      public int getWeightEstimate() {
        return weightEstimate;
      }
    };
  }
}
