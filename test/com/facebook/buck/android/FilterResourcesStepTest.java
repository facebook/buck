/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FilterResourcesStep.ImageScaler;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class FilterResourcesStepTest {

  private static final String first = "/first-path/res";
  private static final String second = "/second-path/res";
  private static final String third = "/third-path/res";

  private static final ImmutableBiMap<Path, Path> inResDirToOutResDirMap =
      ImmutableBiMap.of(
          Paths.get(first), Paths.get("/dest/1"),
          Paths.get(second), Paths.get("/dest/2"),
          Paths.get(third), Paths.get("/dest/3"));
  private static Set<String> qualifiers = ImmutableSet.of("mdpi", "hdpi", "xhdpi");
  private final Filters.Density targetDensity = Filters.Density.MDPI;
  private final File baseDestination = new File("/dest");

  private final Path scaleSource = getDrawableFile(first, "xhdpi", "other.png");
  private final Path scaleDest = getDrawableFile(first, "mdpi", "other.png");

  private Path getDrawableFile(String dir, String qualifier, String filename) {
    return Paths.get(dir, String.format("drawable-%s", qualifier), filename);
  }

  @Test
  public void testFilterDrawables() throws IOException, InterruptedException {

    // Mock a ProjectFilesystem. This will be called into by the image downscaling step.
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getRootPath()).andStubReturn(Paths.get("."));
    EasyMock
      .expect(filesystem.getFileForRelativePath(EasyMock.<Path>anyObject()))
      .andAnswer(new IAnswer<File>(){
          @Override
          public File answer() throws Throwable {
             return ((Path) EasyMock.getCurrentArguments()[0]).toFile();
          }})
      .anyTimes();
    filesystem.createParentDirs(scaleDest);
    EasyMock.expect(filesystem.deleteFileAtPath(scaleSource)).andReturn(true);
    Path scaleSourceDir = scaleSource.getParent();
    EasyMock.expect(filesystem.listFiles(scaleSourceDir)).andReturn(new File[0]);
    EasyMock.expect(filesystem.deleteFileAtPath(scaleSourceDir)).andReturn(true);
    EasyMock.replay(filesystem);

    // Mock an ExecutionContext.
    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    ProcessExecutor processExecutor = EasyMock.createMock(ProcessExecutor.class);
    EasyMock.expect(context.getProcessExecutor()).andReturn(processExecutor).anyTimes();
    EasyMock.expect(context.getVerbosity()).andReturn(Verbosity.SILENT).anyTimes();
    EasyMock.expect(context.getProjectFilesystem()).andReturn(filesystem).anyTimes();
    EasyMock.replay(context);

    // Create a mock DrawableFinder, just creates one drawable/density/resource dir.
    FilterResourcesStep.DrawableFinder finder = EasyMock.createMock(
        FilterResourcesStep.DrawableFinder.class);

    // Create mock FilteredDirectoryCopier to find what we're calling on it.
    FilteredDirectoryCopier copier = EasyMock.createMock(FilteredDirectoryCopier.class);
    // We'll want to see what the filtering command passes to the copier.
    Capture<Map<Path, Path>> dirMapCapture = newCapture();
    Capture<Predicate<Path>> predCapture = newCapture();
    copier.copyDirs(EasyMock.<ProjectFilesystem>anyObject(),
        EasyMock.capture(dirMapCapture),
        EasyMock.capture(predCapture));
    EasyMock.replay(copier);

    ImageScaler scaler = EasyMock.createMock(ImageScaler.class);
    scaler.scale(
        0.5,
        scaleSource,
        scaleDest,
        context);

    EasyMock.expect(scaler.isAvailable(context)).andReturn(true);
    EasyMock.replay(scaler);

    FilterResourcesStep command = new FilterResourcesStep(
        inResDirToOutResDirMap,
        /* filterDrawables */ true,
        /* filterStrings */ true,
        /* whitelistedStringDirs */ ImmutableSet.<Path>of(),
        /* locales */ ImmutableSet.<String>of(),
        copier,
        ImmutableSet.of(targetDensity),
        finder,
        scaler);

    EasyMock
      .expect(finder.findDrawables(inResDirToOutResDirMap.keySet(), filesystem))
      .andAnswer(new IAnswer<Set<Path>>() {
        @SuppressWarnings("unchecked")
        @Override
        public Set<Path> answer() throws Throwable {
          ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
          for (Path dir : (Iterable<Path>) EasyMock.getCurrentArguments()[0]) {
            for (String qualifier : qualifiers) {
              builder.add(getDrawableFile(dir.toString(), qualifier, "some.png"));
            }
          }

          builder.add(scaleSource);

          return builder.build();
        }
      })
      .times(2); // We're calling it in the test as well.

    // Called by the downscaling step.
    EasyMock
      .expect(finder.findDrawables(inResDirToOutResDirMap.values(), filesystem))
      .andAnswer(new IAnswer<Set<Path>>() {
        @SuppressWarnings("unchecked")
        @Override
        public Set<Path> answer() throws Throwable {
          ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
          for (Path dir : (Iterable<Path>) EasyMock.getCurrentArguments()[0]) {
            builder.add(getDrawableFile(dir.toString(), targetDensity.toString(), "some.png"));
          }

          builder.add(scaleSource);
          return builder.build();
        }
      })
      .once();
    EasyMock.replay(finder);

    // We'll use this to verify the source->destination mappings created by the command.
    ImmutableMap.Builder<Path, Path> dirMapBuilder = ImmutableMap.builder();

    Iterator<Path> destIterator = inResDirToOutResDirMap.values().iterator();
    for (Path dir : inResDirToOutResDirMap.keySet()) {
      Path nextDestination = destIterator.next();
      dirMapBuilder.put(dir, nextDestination);

      // Verify that destination path requirements are observed.
      assertEquals(baseDestination, nextDestination.getParent().toFile());
    }

    // Execute command.
    command.execute(context);

    // Ensure resources are copied to the right places.
    assertEquals(dirMapBuilder.build(), dirMapCapture.getValue());

    // Ensure the right filter is created.
    Set<Path> drawables = finder.findDrawables(inResDirToOutResDirMap.keySet(), filesystem);
    Predicate<Path> expectedPred =
        Filters.createImageDensityFilter(drawables, ImmutableSet.of(targetDensity), false);
    Predicate<Path> capturedPred = predCapture.getValue();
    for (Path drawablePath : drawables) {
      assertEquals(expectedPred.apply(drawablePath), capturedPred.apply(drawablePath));
    }

    // We shouldn't need the execution context, should call copyDirs once on the copier,
    // and we're calling finder.findDrawables twice.
    EasyMock.verify(copier, context, finder, filesystem, scaler);
  }

  @Test
  public void testFilterStrings() throws IOException {
    FilteredDirectoryCopier copier = EasyMock.createMock(FilteredDirectoryCopier.class);
    Capture<Predicate<Path>> capturedPredicate = newCapture();
    copier.copyDirs(EasyMock.<ProjectFilesystem>anyObject(),
        EasyMock.<Map<Path, Path>>anyObject(),
        EasyMock.capture(capturedPredicate));
    EasyMock.replay(copier);

    FilterResourcesStep step = new FilterResourcesStep(
        /* inResDirToOutResDirMap */ ImmutableBiMap.<Path, Path>of(),
        /* filterDrawables */ false,
        /* filterStrings */ true,
        /* whitelistedStringDirs */ ImmutableSet.of(Paths.get("com/whitelisted/res")),
        /* locales */ ImmutableSet.<String>of(),
        copier,
        /* targetDensities */ null,
        /* drawableFinder */ null,
        /* imageScaler */ null);

    assertEquals(0, step.execute(TestExecutionContext.newInstance()));
    Predicate<Path> filePredicate = capturedPredicate.getValue();

    assertTrue(filePredicate.apply(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.apply(Paths.get("com/example/res/values/strings.xml")));
    assertTrue(filePredicate.apply(Paths.get("com/whitelisted/res/values-af/strings.xml")));

    assertFalse(filePredicate.apply(Paths.get("com/example/res/values-af/strings.xml")));

    EasyMock.verify(copier);
  }

  @Test
  public void testFilterLocales() throws IOException {
    FilteredDirectoryCopier copier = EasyMock.createMock(FilteredDirectoryCopier.class);
    Capture<Predicate<Path>> capturedPredicate = newCapture();
    copier.copyDirs(EasyMock.<ProjectFilesystem>anyObject(),
        EasyMock.<Map<Path, Path>>anyObject(),
        EasyMock.capture(capturedPredicate));
    EasyMock.replay(copier);

    FilterResourcesStep step = new FilterResourcesStep(
        /* inResDirToOutResDirMap */ ImmutableBiMap.<Path, Path>of(),
        /* filterDrawables */ false,
        /* filterStrings */ false,
        /* whitelistedStringDirs */ ImmutableSet.<Path>of(),
        ImmutableSet.of("es", "es_US"),
        copier,
        /* targetDensities */ null,
        /* drawableFinder */ null,
        /* imageScaler */ null);

    assertEquals(0, step.execute(TestExecutionContext.newInstance()));
    Predicate<Path> filePredicate = capturedPredicate.getValue();

    assertTrue(filePredicate.apply(Paths.get("com/example/res/drawables/image.png")));
    assertTrue(filePredicate.apply(Paths.get("com/example/res/values/strings.xml")));
    assertTrue(filePredicate.apply(Paths.get("com/example/res/values-es/strings.xml")));
    assertTrue(filePredicate.apply(Paths.get("com/example/res/values-es-rUS/strings.xml")));

    assertFalse(filePredicate.apply(Paths.get("com/example/res/values-en/strings.xml")));
    assertFalse(filePredicate.apply(Paths.get("com/example/res/values-es-rES/strings.xml")));

    EasyMock.verify(copier);
  }

  @Test
  public void testNonEnglishStringsPathRegex() {
    assertTrue(matchesRegex("res/values-es/strings.xml"));
    assertFalse(matchesRegex("res/values-/strings.xml"));
    assertTrue(matchesRegex("/res/values-es/strings.xml"));
    assertFalse(matchesRegex("rootres/values-es/strings.xml"));
    assertTrue(matchesRegex("root/res/values-es-rUS/strings.xml"));
  }

  private static boolean matchesRegex(String input) {
    return FilterResourcesStep.NON_ENGLISH_STRING_PATH.matcher(input).matches();
  }
}
