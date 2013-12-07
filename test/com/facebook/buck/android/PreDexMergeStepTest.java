/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.BuckEventBusFactory.CapturingLogEventListener;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.concurrent.FakeListeningExecutorService;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class PreDexMergeStepTest {

  private static final String secondaryDexJarFilesDir =
      "buck-out/gen/app/__app_secondary_dex__/assets/secondary-program-dex-jars";
  private static final Path secondaryDexMetadataTxt =
      Paths.get("buck-out/gen/app/__app_metadata.txt");

  private static final ImmutableSet<String> DEFAULT_PRIMARY_DEX_SUBSTRINGS_FOR_TEST =
      ImmutableSet.of("com/example/init/Init");

  private static final ImmutableList<DexWithClasses> SAMPLE_DEX_FILES_TO_MERGE = ImmutableList.of(
      createDexWithClasses(
          "buck-out/gen/dex1.dex.jar",
          ImmutableSet.of("com/example/common/base/Base"),
          100),
      createDexWithClasses(
          "buck-out/gen/dex2.dex.jar",
          ImmutableSet.of("com/example/common/collect/Lists"),
          200),
      createDexWithClasses(
          "buck-out/gen/dex3.dex.jar",
          ImmutableSet.of("com/example/init/Init", "com/example/init/Init$1"),
          50),
      createDexWithClasses(
          "buck-out/gen/dex4.dex.jar",
          ImmutableSet.of("com/example/common/io/Files", "com/example/common/io/LineReader"),
          250)
      );

  /** All of the steps in this list must be able to be run in parallel. */
  private List<Step> stepsAddedToStepRunnerToBeRunInParallel = Lists.newArrayList();

  private Path scratchDirectory = Paths.get("buck-out/bin/app/__pre_dex_tmp__");
  private StepRunner stepRunner;

  /** This will be returned by the {@link #stepRunner}. */
  private FakeListeningExecutorService executorService;

  @Before
  public void setUp() {
    executorService = new FakeListeningExecutorService();
    stepRunner = new FakeStepRunner(executorService) {
      @Override
      public void runStepsInParallelAndWait(List<Step> steps)
          throws StepFailedException {
        stepsAddedToStepRunnerToBeRunInParallel.addAll(steps);
      }
    };
  }

  @Test
  public void testMultipleDexesWorkAsExpected() throws IOException {
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);

    // Mock the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    configureResolveMethod(projectFilesystem);
    configureCanarySetup(projectFilesystem, 2);

    setExpectedSecondaryDexFiles(projectFilesystem, 2, DexStore.JAR, /* computeSha1 */ true);
    ImmutableList<String> lines = ImmutableList.of(
        "secondary-1.dex.jar a451b51 secondary.dex01.Canary",
        "secondary-2.dex.jar 6e279aa secondary.dex02.Canary");
    projectFilesystem.writeLinesToPath(lines, secondaryDexMetadataTxt);

    // Mock the ExecutionContext.
    AndroidPlatformTarget androidPlatformTarget = EasyMock.createMock(AndroidPlatformTarget.class);
    EasyMock.expect(androidPlatformTarget.getDxExecutable())
        .andReturn(new File("/usr/bin/dx")).anyTimes();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .setAndroidPlatformTarget(Optional.of(androidPlatformTarget))
        .build();

    EasyMock.replay(projectFilesystem, androidPlatformTarget);
    int exitCode = preDexMergeStep.execute(context);
    assertEquals(0, exitCode);
    assertTrue(executorService.wasShutdownNowInvoked());

    String dxCommandPrefix = createDxCommandPrefix();
    MoreAsserts.assertSteps(
        "There should be three dx steps: one for the primary dex, and one for each secondary dex.",
        ImmutableList.of(
            dxCommandPrefix + "buck-out/gen/app/__app_classes.dex " +
                "buck-out/gen/app/r_classes.dex.jar " +
                "buck-out/gen/dex3.dex.jar",
            dxCommandPrefix + secondaryDexJarFilesDir + "/secondary-1.dex.jar " +
                scratchDirectory + "/canary_1 " +
                "buck-out/gen/dex1.dex.jar " +
                "buck-out/gen/dex2.dex.jar",
            dxCommandPrefix + secondaryDexJarFilesDir + "/secondary-2.dex.jar " +
                scratchDirectory + "/canary_2 " +
                "buck-out/gen/dex4.dex.jar"),
        stepsAddedToStepRunnerToBeRunInParallel,
        context);
    EasyMock.verify(projectFilesystem, androidPlatformTarget);
  }

  @Test
  public void testMultipleXzDexesWorkAsExpected() throws IOException {
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 550,
        DexStore.XZ);

    // Mock the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    configureResolveMethod(projectFilesystem);
    configureCanarySetup(projectFilesystem, 1);
    setExpectedSecondaryDexFiles(projectFilesystem, 1, DexStore.XZ, /* computeSha1 */ true);
    ImmutableList<String> lines = ImmutableList.of(
        "secondary-1.dex.jar.xz 56175a8 secondary.dex01.Canary");
    projectFilesystem.writeLinesToPath(lines, secondaryDexMetadataTxt);

    // Mock the ExecutionContext.
    AndroidPlatformTarget androidPlatformTarget = EasyMock.createMock(AndroidPlatformTarget.class);
    EasyMock.expect(androidPlatformTarget.getDxExecutable())
        .andReturn(new File("/usr/bin/dx")).anyTimes();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .setAndroidPlatformTarget(Optional.of(androidPlatformTarget))
        .build();

    EasyMock.replay(projectFilesystem, androidPlatformTarget);
    int exitCode = preDexMergeStep.execute(context);
    assertEquals(0, exitCode);

    assertTrue(executorService.wasShutdownNowInvoked());
    String dxCommandPrefix = createDxCommandPrefix();

    // There is a composite step that does the dx-repack-rm-xz work.
    String tmpJar = secondaryDexJarFilesDir + "/secondary-1.dex.tmp.jar";
    String tmpDxJar = secondaryDexJarFilesDir + "/secondary-1.dex.jar";
    String dxStep = dxCommandPrefix + tmpJar + " " +
        scratchDirectory + "/canary_1 " +
        "buck-out/gen/dex1.dex.jar " +
        "buck-out/gen/dex2.dex.jar " +
        "buck-out/gen/dex4.dex.jar";
    String repackStep = "repack " + tmpJar + " in " + tmpDxJar;
    String rmStep = "rm -f " + tmpJar;
    String xzStep = "xz -z -4 --check=crc32 " + tmpDxJar;
    String dxRepackRmXzCompositeStep = Joiner.on(" && ").join(dxStep, repackStep, rmStep, xzStep);

    MoreAsserts.assertSteps(
        "There should be one simple dx step and one dx-repack-rm-xz composite step.",
        ImmutableList.of(
            dxCommandPrefix + "buck-out/gen/app/__app_classes.dex " +
                "buck-out/gen/app/r_classes.dex.jar " +
                "buck-out/gen/dex3.dex.jar",
            dxRepackRmXzCompositeStep),
        stepsAddedToStepRunnerToBeRunInParallel,
        context);
    EasyMock.verify(projectFilesystem, androidPlatformTarget);
  }

  @Test
  public void testPrimaryDexExceedsLinearAllocFailsWithExitCodeOne() {
    DexWithClasses dex1 = createDexWithClasses(
        "buck-out/gen/dex1.dex.jar",
        ImmutableSet.of("com/example/init/Init"),
        200);
    DexWithClasses dex2 = createDexWithClasses(
        "buck-out/gen/dex2.dex.jar",
        ImmutableSet.of("com/example/init/Init$1"),
        1);
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(
        ImmutableList.of(dex1, dex2),
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingLogEventListener listener = new BuckEventBusFactory.CapturingLogEventListener();
    eventBus.register(listener);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setEventBus(eventBus)
        .build();
    int exitCode = preDexMergeStep.execute(context);
    assertEquals(1, exitCode);
    assertEquals(
        "There should be 100 from the R.class dex, 200 from dex1.dex.jar, and 1 from dex2.dex.jar.",
        ImmutableList.of("DexWithClasses buck-out/gen/dex2.dex.jar with cost 1 puts the linear "
            + "alloc estimate for the primary dex at 301, exceeding the maximum of 300."),
        listener.getErrorMessages());
  }

  @Test
  public void testDexWithClassesExceedsLinearAllocFailsWithExitCodeOne() {
    DexWithClasses dex1 = createDexWithClasses(
        "buck-out/gen/dex1.dex.jar",
        ImmutableSet.of("com/example/Foo"),
        350);
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(
        ImmutableList.of(dex1),
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingLogEventListener listener = new BuckEventBusFactory.CapturingLogEventListener();
    eventBus.register(listener);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setEventBus(eventBus)
        .build();
    int exitCode = preDexMergeStep.execute(context);
    assertEquals(1, exitCode);
    assertEquals(
        ImmutableList.of("DexWithClasses buck-out/gen/dex1.dex.jar with cost 350 exceeds the max " +
            "cost 300 for a secondary dex file."),
        listener.getErrorMessages());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWriteMetadataTxtIoExceptionFailsWithExitCodeOne() throws IOException {
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);

    // Mock the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    configureResolveMethod(projectFilesystem);
    configureCanarySetup(projectFilesystem, 2);
    projectFilesystem.writeLinesToPath(EasyMock.isA(Iterable.class),
        EasyMock.isA(Path.class));
    EasyMock.expectLastCall().andThrow(new IOException());
    setExpectedSecondaryDexFiles(projectFilesystem, 2, DexStore.JAR, /* computeSha1 */ true);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingLogEventListener listener = new BuckEventBusFactory.CapturingLogEventListener();
    eventBus.register(listener);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .setEventBus(eventBus)
        .build();

    EasyMock.replay(projectFilesystem);

    int exitCode = preDexMergeStep.execute(context);
    assertEquals(1, exitCode);
    assertEquals(ImmutableList.of("Failed when writing metadata.txt multi-dex."),
        listener.getErrorMessages());

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testStepFailedExceptionFailsWithExitCodeOne() throws IOException {
    stepRunner = new FakeStepRunner(executorService) {
      @Override
      public void runStepsInParallelAndWait(List<Step> steps) throws StepFailedException {
        throw new StepFailedException(
            "Step failed in runStepsInParallelAndWait().",
            steps.get(0),
            /* exitCode */ 1);
      }
    };
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);

    // Mock the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    configureResolveMethod(projectFilesystem);
    configureCanarySetup(projectFilesystem, 2);
    setExpectedSecondaryDexFiles(projectFilesystem, 2, DexStore.JAR, /* computeSha1 */ false);

    // Mock the ExecutionContext.
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    EasyMock.replay(projectFilesystem);
    int exitCode = preDexMergeStep.execute(context);
    assertEquals("Step should exit with non-zero exit code: should not throw.", 1, exitCode);
    assertTrue(executorService.wasShutdownNowInvoked());

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testGetShortName() {
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);
    assertEquals("bucket_and_merge_dx", preDexMergeStep.getShortName());
  }

  @Test
  public void testGetDescription() {
    PreDexMergeStep preDexMergeStep = createPreDexMergeStep(SAMPLE_DEX_FILES_TO_MERGE,
        /* linearAllocHardLimit */ 300,
        DexStore.JAR);
    ExecutionContext context = TestExecutionContext.newInstance();
    assertEquals("bucket_and_merge_dx", preDexMergeStep.getDescription(context));
  }

  /**
   * Creates a new {@link PreDexMergeStep} with the specified parameters. The new
   * {@link PreDexMergeStep} will use {@link #DEFAULT_PRIMARY_DEX_SUBSTRINGS_FOR_TEST} for the
   * {@code primaryDexSubstrings} param, and
   * {@link PreDexMergeStep#createStepRunner(ExecutionContext)} will return {@link #stepRunner}.
   */
  private PreDexMergeStep createPreDexMergeStep(ImmutableList<DexWithClasses> dexFilesToMerge,
      long linearAllocHardLimit,
      DexStore dexStore) {
    DexWithClasses rDotJavaDex = new FakeDexWithClasses(
        "buck-out/gen/app/r_classes.dex.jar",
        ImmutableSet.of("com.example.R", "com.example.R$id"),
        /* linearAllocSize */ 100);

    String primaryDexPath = "buck-out/gen/app/__app_classes.dex";

    return new PreDexMergeStep(
        dexFilesToMerge,
        /* dexWithClassesForRDotJava */ Optional.of(rDotJavaDex),
        primaryDexPath,
        DEFAULT_PRIMARY_DEX_SUBSTRINGS_FOR_TEST,
        secondaryDexMetadataTxt,
        secondaryDexJarFilesDir,
        dexStore,
        linearAllocHardLimit,
        scratchDirectory) {

      @Override
      protected StepRunner createStepRunner(ExecutionContext context) {
        return stepRunner;
      }
    };
  }

  private void setExpectedSecondaryDexFiles(ProjectFilesystem projectFilesystem,
      int numSecondary,
      DexStore dexStore,
      boolean computeSha1) {
    for (int index = 1; index <= numSecondary; index++) {
      String extension = index + dexStore.getExtension();
      String path = secondaryDexJarFilesDir + "/secondary-" + extension;

      if (computeSha1) {
        try {
          EasyMock.expect(projectFilesystem.computeSha1(Paths.get(path))).andAnswer(
              new IAnswer<String> () {
                @Override
                public String answer() throws Throwable {
                  Path arg = (Path) EasyMock.getCurrentArguments()[0];
                  String name = arg.getFileName().toString();
                  switch (name) {
                  case "secondary-1.dex.jar":
                    return "a451b51";
                  case "secondary-2.dex.jar":
                    return "6e279aa";
                  case "secondary-1.dex.jar.xz":
                    return "56175a8";
                  case "secondary-2.dex.jar.xz":
                    return "b5e0b31";
                  default:
                    throw new IllegalArgumentException("Unrecognized file: " + name);
                  }
                }
          });
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private void configureResolveMethod(ProjectFilesystem projectFilesystem) {
    EasyMock.expect(projectFilesystem.resolve(EasyMock.isA(Path.class)))
        .andAnswer(new IAnswer<Path>() {
      @Override
      public Path answer() throws Throwable {
        Path arg = (Path) EasyMock.getCurrentArguments()[0];
        return arg;
      }
    }).anyTimes();
  }

  private void configureCanarySetup(ProjectFilesystem projectFilesystem, int num)
      throws IOException {
    for (int i = 1; i <= num; i++) {
      Path classFile = scratchDirectory.resolve(
          "canary_" + i + "/secondary/dex0" + i + "/Canary.class");
      projectFilesystem.createParentDirs(classFile);
      projectFilesystem.copyToPath(capture(new Capture<InputStream>()), eq(classFile));
    }
  }

  private String createDxCommandPrefix() {
    String dxJvmArgs = "";

    return "/usr/bin/dx" + dxJvmArgs + " --dex --no-optimize --output ";
  }

  private static DexWithClasses createDexWithClasses(String pathToDexFile,
      ImmutableSet<String> classNames,
      int linearAllocSize) {
    return new FakeDexWithClasses(pathToDexFile, classNames, linearAllocSize);
  }

  private static class FakeDexWithClasses implements DexWithClasses {

    private final Path pathToDexFile;
    private final ImmutableSet<String> classNames;
    private final int linearAllocSize;

    public FakeDexWithClasses(String pathToDexFile,
        ImmutableSet<String> classNames,
        int linearAllocSize) {
      this.pathToDexFile = Paths.get(pathToDexFile);
      this.classNames = classNames;
      this.linearAllocSize = linearAllocSize;
    }

    @Override
    public Path getPathToDexFile() {
      return pathToDexFile;
    }

    @Override
    public ImmutableSet<String> getClassNames() {
      return classNames;
    }

    @Override
    public int getSizeEstimate() {
      return linearAllocSize;
    }

    @Override
    public String toString() {
      return pathToDexFile.toString();
    }
  }

  /**
   * Fake that provides a default implementation for {@link #getListeningExecutorService}, but
   * requires subclasses to override {@link #runStepsInParallelAndWait(List)}. This fake is very
   * specific to this test case.
   */
  private static abstract class FakeStepRunner implements StepRunner {

    private final ListeningExecutorService executorService;

    private FakeStepRunner(ListeningExecutorService executorService) {
      this.executorService = Preconditions.checkNotNull(executorService);
    }

    @Override
    public ListeningExecutorService getListeningExecutorService() {
      return executorService;
    }

    @Override
    public void runStep(Step step) throws StepFailedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void runStepForBuildTarget(Step step, BuildTarget buildTarget)
        throws StepFailedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> ListenableFuture<T> runStepsAndYieldResult(List<Step> steps,
        Callable<T> interpretResults, BuildTarget buildTarget) {
      throw new UnsupportedOperationException();
    }
  }
}
