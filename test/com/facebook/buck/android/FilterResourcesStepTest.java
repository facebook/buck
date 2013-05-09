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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Test;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class FilterResourcesStepTest {

  private final static ImmutableSet<String> resDirectories = ImmutableSet.of(
      "/first-path/res",
      "/second-path/res",
      "/third-path/res");
  private static Set<String> qualifiers = ImmutableSet.of("mdpi", "hdpi", "xhdpi");
  private final String targetDensity = "mdpi";
  private final File baseDestination = new File("/dest");

  @Test
  public void testFilterResourcesCommand() {

    // Mock an ExecutionContext.
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ProcessExecutor processExecutor = EasyMock.createMock(ProcessExecutor.class);
    EasyMock.expect(executionContext.getProcessExecutor()).andReturn(processExecutor).anyTimes();
    EasyMock.replay(executionContext);

    // Create a mock DrawableFinder, just creates one drawable/density/resource dir.
    FilterResourcesStep.DrawableFinder finder = EasyMock.createMock(
        FilterResourcesStep.DrawableFinder.class);

    EasyMock.expect(finder.findDrawables(resDirectories)).andAnswer(new IAnswer<Set<String>>() {
      @SuppressWarnings("unchecked")
      @Override
      public Set<String> answer() throws Throwable {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (String dir : (Iterable<String>) EasyMock.getCurrentArguments()[0]) {
          for (String qualifier : qualifiers) {
            builder.add(new File(dir, String.format("drawable-%s/some.png", qualifier)).getPath());
          }
        }
        return builder.build();
      }
    }).times(2); // We're calling it in the test as well.
    EasyMock.replay(finder);

    // Create mock FilteredDirectoryCopier to find what we're calling on it.
    FilteredDirectoryCopier copier = EasyMock.createMock(FilteredDirectoryCopier.class);
    // We'll want to see what the filtering command passes to the copier.
    Capture<Map<String, String>> dirMapCapture = new Capture<Map<String, String>>();
    Capture<Predicate<File>> predCapture = new Capture<Predicate<File>>();
    Capture<ProcessExecutor> processExecutorCapture = new Capture<ProcessExecutor>();
    copier.copyDirs(EasyMock.capture(dirMapCapture),
        EasyMock.capture(predCapture),
        EasyMock.capture(processExecutorCapture));
    EasyMock.expectLastCall().once();
    EasyMock.replay(copier);

    FilterResourcesStep command = new FilterResourcesStep(
        resDirectories,
        baseDestination,
        targetDensity,
        copier,
        finder);

    // We'll use this to verify the source->destination mappings created by the command.
    ImmutableMap.Builder<String, String> dirMapBuilder = ImmutableMap.builder();

    Iterator<String> destIterator = command.getFilteredResourceDirectories().iterator();
    for(String dir : resDirectories) {
      String nextDestination = destIterator.next();
      dirMapBuilder.put(dir, nextDestination);

      // Verify that destination path requirements are observed.
      assertEquals(baseDestination, new File(nextDestination).getParentFile());
    }

    // Execute command.
    command.execute(executionContext);

    // Ensure resources are copied to the right places.
    assertEquals(dirMapBuilder.build(), dirMapCapture.getValue());

    // Ensure the right filter is created.
    Set<String> drawables = finder.findDrawables(resDirectories);
    Predicate<File> expectedPred = Filters.createImageDensityFilter(drawables, targetDensity);
    Predicate<File> capturedPred = predCapture.getValue();
    for (String drawablePath : drawables) {
      File drawableFile = new File(drawablePath);
      assertEquals(expectedPred.apply(drawableFile), capturedPred.apply(drawableFile));
    }

    // We shouldn't need the execution context, should call copyDirs once on the copier,
    // and we're calling finder.findDrawables twice.
    EasyMock.verify(copier, executionContext, finder);
  }
}
