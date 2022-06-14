/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FilterResourcesSteps.ImageScaler;
import com.facebook.buck.android.resources.filter.ResourceFilters;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class FilterResourcesStepTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private Path getDrawableFile(String dir, String qualifier, String filename) {
    return Paths.get(dir, String.format("drawable-%s", qualifier), filename);
  }

  @Test
  public void testFilterDrawables() throws IOException, InterruptedException {
    final String first = "first-path/res";
    Path baseDestination = Paths.get("dest");
    ImmutableBiMap<Path, Path> inResDirToOutResDirMap =
        ImmutableBiMap.of(
            Paths.get(first), baseDestination.resolve("1"),
            Paths.get("second-path/res"), baseDestination.resolve("2"),
            Paths.get("third-path/res"), baseDestination.resolve("3"));

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());

    Path scaleSource = getDrawableFile(first, "xhdpi", "other.png");
    filesystem.createNewFile(scaleSource);

    // Create our drawables.
    for (Path dir : inResDirToOutResDirMap.keySet()) {
      for (String qualifier : ImmutableSet.of("mdpi", "hdpi", "xhdpi")) {
        Path drawableFile = getDrawableFile(dir.toString(), qualifier, "some.png");
        ProjectFilesystemUtils.createParentDirs(tmpFolder.getRoot(), drawableFile);
        ProjectFilesystemUtils.createNewFile(tmpFolder.getRoot(), drawableFile);
      }
    }

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            inResDirToOutResDirMap,
            /* filterByDensity */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(ResourceFilters.Density.MDPI),
            new ImageScaler() {
              @Override
              public boolean isAvailable(StepExecutionContext context) {
                return true;
              }

              @Override
              public void scale(
                  double factor, Path source, Path destination, StepExecutionContext context) {}
            });

    // We'll use this to verify the source->destination mappings created by the command.
    ImmutableMap.Builder<Path, Path> dirMapBuilder = ImmutableMap.builder();

    Iterator<Path> destIterator = inResDirToOutResDirMap.values().iterator();
    for (Path dir : inResDirToOutResDirMap.keySet()) {
      Path nextDestination = destIterator.next();
      dirMapBuilder.put(dir, nextDestination);

      // Verify that destination path requirements are observed.
      assertEquals(baseDestination.toFile(), nextDestination.getParent().toFile());
    }

    // Execute commands
    assertThat(
        filterResourcesSteps
            .getCopyStep()
            .execute(TestExecutionContext.newInstance())
            .getExitCode(),
        Matchers.is(0));
    assertThat(
        filterResourcesSteps
            .getScaleStep()
            .execute(TestExecutionContext.newInstance())
            .getExitCode(),
        Matchers.is(0));

    assertTrue(
        ProjectFilesystemUtils.isFile(
            tmpFolder.getRoot(), baseDestination.resolve("1/drawable-mdpi/some.png")));
  }

  @Test
  public void testWhitelistFilter() throws IOException {
    Predicate<Path> filePredicate =
        getTestPathPredicate(
            true,
            ImmutableSet.of(Paths.get("com/whitelisted/res")),
            ImmutableSet.of(),
            ImmutableSet.of());

    assertTrue(filePredicate.test(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/whitelisted/res/values-af/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-af/integers.xml")));

    assertFalse(filePredicate.test(Paths.get("com/example/res/values-af/strings.xml")));
  }

  @Test
  public void testFilterPackagedLocales() throws IOException {
    Predicate<Path> filePredicate =
        getTestPathPredicate(true, ImmutableSet.of(), ImmutableSet.of(), ImmutableSet.of("ru"));

    assertTrue(filePredicate.test(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values/strings.xml")));

    assertTrue(filePredicate.test(Paths.get("com/example/res/values-ru/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-ru/integers.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es/integers.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-en/integers.xml")));

    assertFalse(filePredicate.test(Paths.get("com/example/res/values-es/strings.xml")));
    assertFalse(filePredicate.test(Paths.get("com/example/res/values-es-rUS/strings.xml")));
  }

  @Test
  public void testFilterLocales() throws IOException {
    Predicate<Path> filePredicate =
        getTestPathPredicate(
            false, ImmutableSet.of(), ImmutableSet.of("es", "es_US"), ImmutableSet.of());

    assertTrue(filePredicate.test(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es-rUS/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es/integers.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-en/integers.xml")));

    assertFalse(filePredicate.test(Paths.get("com/example/res/values-en/strings.xml")));
    assertFalse(filePredicate.test(Paths.get("com/example/res/values-es-rES/strings.xml")));
  }

  @Test
  public void testUsingWhitelistIgnoresLocaleFilter() throws IOException {
    Predicate<Path> filePredicate =
        getTestPathPredicate(
            true,
            ImmutableSet.of(Paths.get("com/example/res")),
            ImmutableSet.of("es", "es_US"),
            ImmutableSet.of());

    assertTrue(filePredicate.test(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es-rUS/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es/integers.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-en/integers.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-en/strings.xml")));
    assertTrue(filePredicate.test(Paths.get("com/example/res/values-es-rES/strings.xml")));
  }

  @Test
  public void testNonEnglishStringsPathRegex() {
    assertMatchesRegex("path/res/values-es/strings.xml", "es", null);
    assertNotMatchesRegex("res/values/strings.xml");
    assertNotMatchesRegex("res/values-es/integers.xml");
    assertNotMatchesRegex("res/values-/strings.xml");
    assertMatchesRegex("/res/values-es/strings.xml", "es", null);
    assertNotMatchesRegex("rootres/values-es/strings.xml");
    assertMatchesRegex("root/res/values-es-rUS/strings.xml", "es", "US");
  }

  @Test
  public void nonDrawableResourcesFiltered() throws IOException, InterruptedException {
    ResourceFilters.Density targetDensity = ResourceFilters.Density.MDPI;
    ResourceFilters.Density excludedDensity = ResourceFilters.Density.LDPI;
    final String file = "somefile";
    Path resDir = Paths.get("res");
    Path resOutDir = Paths.get("res-out");

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable")) {
        continue;
      }

      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, targetDensity)).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, targetDensity)).resolve(file));
      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, excludedDensity)).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, excludedDensity)).resolve(file));
    }

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            ImmutableBiMap.of(resDir, resOutDir),
            /* filterByDPI */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(targetDensity),
            /* imageScaler */ null);
    filterResourcesSteps.getCopyStep().execute(null);
    filterResourcesSteps.getScaleStep().execute(null);

    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable")) {
        continue;
      }
      assertTrue(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir.resolve(String.format("%s-%s", folderName, targetDensity)).resolve(file)));
      assertFalse(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir
                  .resolve(String.format("%s-%s", folderName, excludedDensity))
                  .resolve(file)));
    }
  }

  @Test
  public void xmlDrawableResourcesFiltered() throws IOException, InterruptedException {
    ResourceFilters.Density targetDensity = ResourceFilters.Density.MDPI;
    ResourceFilters.Density excludedDensity = ResourceFilters.Density.LDPI;
    final String file = "somefile.xml";
    Path resDir = Paths.get("res");
    Path resOutDir = Paths.get("res-out");

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    ProjectFilesystemUtils.createParentDirs(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("drawable-%s", targetDensity)).resolve(file));
    ProjectFilesystemUtils.createNewFile(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("drawable-%s", targetDensity)).resolve(file));
    ProjectFilesystemUtils.createParentDirs(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("drawable-%s", excludedDensity)).resolve(file));
    ProjectFilesystemUtils.createNewFile(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("drawable-%s", excludedDensity)).resolve(file));

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            ImmutableBiMap.of(resDir, resOutDir),
            /* filterByDPI */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(targetDensity),
            /* imageScaler */ null);
    filterResourcesSteps.getCopyStep().execute(null);
    filterResourcesSteps.getScaleStep().execute(null);

    assertTrue(
        ProjectFilesystemUtils.exists(
            tmpFolder.getRoot(),
            resOutDir.resolve(String.format("drawable-%s", targetDensity)).resolve(file)));
    assertFalse(
        ProjectFilesystemUtils.exists(
            tmpFolder.getRoot(),
            resOutDir.resolve(String.format("drawable-%s", excludedDensity)).resolve(file)));
  }

  @Test
  public void fallsBackToDefaultWhenAllTargetsNotPresent()
      throws IOException, InterruptedException {
    ResourceFilters.Density targetDensity = ResourceFilters.Density.MDPI;
    ResourceFilters.Density providedDensity = ResourceFilters.Density.TVDPI;
    final String file = "somefile.xml";
    Path resDir = Paths.get("res/foo/bar");
    Path resOutDir = Paths.get("res-out");

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable") || folderName.equals("values")) {
        continue;
      }

      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(), resDir.resolve(folderName).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(), resDir.resolve(folderName).resolve(file));
      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, providedDensity)).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, providedDensity)).resolve(file));
    }

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            ImmutableBiMap.of(resDir, resOutDir),
            /* filterByDPI */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(targetDensity),
            /* imageScaler */ null);
    filterResourcesSteps.getCopyStep().execute(null);
    filterResourcesSteps.getScaleStep().execute(null);

    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable") || folderName.equals("values")) {
        continue;
      }
      assertTrue(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(), resOutDir.resolve(folderName).resolve(file)));
      assertFalse(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir.resolve(String.format("%s-%s", folderName, targetDensity))));
      assertFalse(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir
                  .resolve(String.format("%s-%s", folderName, providedDensity))
                  .resolve(file)));
    }
  }

  @Test
  public void fallsBackToDefaultWhenOneTargetNotPresent() throws IOException, InterruptedException {
    ResourceFilters.Density targetDensityIncluded = ResourceFilters.Density.MDPI;
    ResourceFilters.Density targetDensityExcluded = ResourceFilters.Density.XHDPI;
    final String file = "somefile";
    Path resDir = Paths.get("res/foo/bar");
    Path resOutDir = Paths.get("res-out");

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable") || folderName.equals("values")) {
        continue;
      }

      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(), resDir.resolve(folderName).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(), resDir.resolve(folderName).resolve(file));
      ProjectFilesystemUtils.createParentDirs(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, targetDensityIncluded)).resolve(file));
      ProjectFilesystemUtils.createNewFile(
          tmpFolder.getRoot(),
          resDir.resolve(String.format("%s-%s", folderName, targetDensityIncluded)).resolve(file));
    }

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            ImmutableBiMap.of(resDir, resOutDir),
            /* filterByDPI */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(targetDensityIncluded, targetDensityExcluded),
            /* imageScaler */ null);
    filterResourcesSteps.getCopyStep().execute(null);
    filterResourcesSteps.getScaleStep().execute(null);

    for (String folderName : ResourceFilters.SUPPORTED_RESOURCE_DIRECTORIES) {
      if (folderName.equals("drawable") || folderName.equals("values")) {
        continue;
      }
      assertTrue(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(), resOutDir.resolve(folderName).resolve(file)));
      assertTrue(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir
                  .resolve(String.format("%s-%s", folderName, targetDensityIncluded))
                  .resolve(file)));
      assertFalse(
          ProjectFilesystemUtils.exists(
              tmpFolder.getRoot(),
              resOutDir.resolve(String.format("%s-%s", folderName, targetDensityExcluded))));
    }
  }

  @Test
  public void valuesAlwaysIncludesFallback() throws IOException, InterruptedException {
    ResourceFilters.Density targetDensity = ResourceFilters.Density.MDPI;
    final String file = "somefile.xml";
    Path resDir = Paths.get("res/foo/bar");
    Path resOutDir = Paths.get("res-out");

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot());
    ProjectFilesystemUtils.createParentDirs(
        tmpFolder.getRoot(), resDir.resolve("values").resolve(file));
    ProjectFilesystemUtils.createNewFile(
        tmpFolder.getRoot(), resDir.resolve("values").resolve(file));
    ProjectFilesystemUtils.createParentDirs(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("values-%s", targetDensity)).resolve(file));
    ProjectFilesystemUtils.createNewFile(
        tmpFolder.getRoot(),
        resDir.resolve(String.format("values-%s", targetDensity)).resolve(file));

    FilterResourcesSteps filterResourcesSteps =
        new FilterResourcesSteps(
            filesystem,
            ImmutableBiMap.of(resDir, resOutDir),
            /* filterByDPI */ true,
            /* enableStringWhitelisting */ false,
            /* whitelistedStringDirs */ ImmutableSet.of(),
            /* packaged locales */ ImmutableSet.of(),
            /* locales */ ImmutableSet.of(),
            ImmutableSet.of(targetDensity),
            /* imageScaler */ null);
    filterResourcesSteps.getCopyStep().execute(null);
    filterResourcesSteps.getScaleStep().execute(null);

    assertTrue(
        ProjectFilesystemUtils.exists(
            tmpFolder.getRoot(), resOutDir.resolve("values").resolve(file)));
    assertTrue(
        ProjectFilesystemUtils.exists(
            tmpFolder.getRoot(),
            resOutDir.resolve(String.format("values-%s", targetDensity)).resolve(file)));
  }

  private static void assertMatchesRegex(String path, String language, String country) {
    Matcher matcher = FilterResourcesSteps.NON_ENGLISH_STRINGS_FILE_PATH.matcher(path);
    assertTrue(matcher.matches());
    assertEquals(language, matcher.group(1));
    assertEquals(country, matcher.group(2));
  }

  private static void assertNotMatchesRegex(String path) {
    assertFalse(FilterResourcesSteps.NON_ENGLISH_STRINGS_FILE_PATH.matcher(path).matches());
  }

  private static Predicate<Path> getTestPathPredicate(
      boolean enableStringWhitelisting,
      ImmutableSet<Path> whitelistedStringDirs,
      ImmutableSet<String> locales,
      ImmutableSet<String> packagedLocales)
      throws IOException {
    FilterResourcesSteps step =
        new FilterResourcesSteps(
            null,
            /* inResDirToOutResDirMap */ ImmutableBiMap.of(),
            /* filterByDensity */ false,
            /* enableStringWhitelisting */ enableStringWhitelisting,
            /* whitelistedStringDirs */ whitelistedStringDirs,
            /* packaged locales */ packagedLocales,
            /* locales */ locales,
            /* targetDensities */ null,
            /* imageScaler */ null);

    return step.getFilteringPredicate(TestExecutionContext.newInstance());
  }
}
