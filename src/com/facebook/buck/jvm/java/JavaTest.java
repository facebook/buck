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

package com.facebook.buck.jvm.java;

import com.facebook.buck.android.device.TargetDevice;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.ExternalTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class JavaTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestRule,
        HasClasspathEntries,
        HasRuntimeDeps,
        HasPostBuildSteps,
        ExternalTestRunnerRule,
        ExportDependencies {

  public static final Flavor COMPILED_TESTS_LIBRARY_FLAVOR = InternalFlavor.of("testsjar");

  // TODO(#9027062): Migrate this to a PackagedResource so we don't make assumptions
  // about the ant build.
  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes", new File("ant-out/testrunner/classes").getAbsolutePath()));

  private final JavaLibrary compiledTestsLibrary;

  private final Optional<AdditionalClasspathEntriesProvider> additionalClasspathEntriesProvider;

  private final Tool javaRuntimeLauncher;

  private final ImmutableList<Arg> vmArgs;

  private final ImmutableMap<String, String> nativeLibsEnvironment;

  @Nullable private CompiledClassFileFinder compiledClassFileFinder;

  private final ImmutableSet<String> labels;

  private final ImmutableSet<String> contacts;

  private final Optional<Level> stdOutLogLevel;
  private final Optional<Level> stdErrLogLevel;

  private final TestType testType;

  private final int targetJavaVersion;

  private final Optional<Long> testRuleTimeoutMs;

  private final Optional<Long> testCaseTimeoutMs;

  private final ImmutableMap<String, Arg> env;

  private final Path pathToTestLogs;

  private static final int TEST_CLASSES_SHUFFLE_SEED = 0xFACEB00C;

  private static final Logger LOG = Logger.get(JavaTest.class);

  @Nullable private ImmutableList<JUnitStep> junits;

  @Nullable private JUnitStep externalJunitStep;

  private final boolean runTestSeparately;

  private final ForkMode forkMode;

  private final Optional<SourcePath> unbundledResourcesRoot;

  public JavaTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavaLibrary compiledTestsLibrary,
      Optional<AdditionalClasspathEntriesProvider> additionalClasspathEntriesProvider,
      Set<String> labels,
      Set<String> contacts,
      TestType testType,
      String targetLevel,
      Tool javaRuntimeLauncher,
      List<Arg> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<Long> testRuleTimeoutMs,
      Optional<Long> testCaseTimeoutMs,
      ImmutableMap<String, Arg> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel,
      Optional<SourcePath> unbundledResourcesRoot) {
    super(buildTarget, projectFilesystem, params);
    this.compiledTestsLibrary = compiledTestsLibrary;
    this.additionalClasspathEntriesProvider = additionalClasspathEntriesProvider;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.nativeLibsEnvironment = ImmutableMap.copyOf(nativeLibsEnvironment);
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.testType = testType;
    this.targetJavaVersion = JavaVersion.getMajorVersionFromString(targetLevel);
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.testCaseTimeoutMs = testCaseTimeoutMs;
    this.env = env;
    this.runTestSeparately = runTestSeparately;
    this.forkMode = forkMode;
    this.stdOutLogLevel = stdOutLogLevel;
    this.stdErrLogLevel = stdErrLogLevel;
    this.unbundledResourcesRoot = unbundledResourcesRoot;
    this.pathToTestLogs = getPathToTestOutputDirectory().resolve("logs.txt");
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  /** */
  protected ImmutableSet<Path> getBootClasspathEntries() {
    return ImmutableSet.of();
  }

  private Path getClassPathFile() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/classpath-file");
  }

  private JUnitStep getJUnitStep(
      ExecutionContext executionContext,
      SourcePathResolverAdapter pathResolver,
      TestRunningOptions options,
      Optional<Path> outDir,
      Optional<Path> robolectricLogPath,
      Set<String> testClassNames) {

    Iterable<String> reorderedTestClasses =
        reorderClasses(testClassNames, options.isShufflingTests());

    ImmutableList<String> properVmArgs =
        amendVmArgs(
            Arg.stringify(this.vmArgs, pathResolver),
            pathResolver,
            executionContext.getTargetDevice(),
            options.getJavaTempDir());

    BuckEventBus buckEventBus = executionContext.getBuckEventBus();
    BuildId buildId = buckEventBus.getBuildId();
    TestSelectorList testSelectorList = options.getTestSelectorList();
    JUnitJvmArgs args =
        ImmutableJUnitJvmArgs.builder()
            .setTargetJavaVersion(targetJavaVersion)
            .setTestType(testType)
            .setDirectoryForTestResults(outDir)
            .setClasspathFile(getClassPathFile())
            .setTestRunnerClasspath(TESTRUNNER_CLASSES)
            .setCodeCoverageEnabled(executionContext.isCodeCoverageEnabled())
            .setInclNoLocationClassesEnabled(executionContext.isInclNoLocationClassesEnabled())
            .setDebugEnabled(executionContext.isDebugEnabled())
            .setPathToJavaAgent(options.getPathToJavaAgent())
            .setBuildId(buildId)
            .setBuckModuleBaseSourceCodePath(
                getBuildTarget()
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(getProjectFilesystem().getFileSystem()))
            .setStdOutLogLevel(stdOutLogLevel)
            .setStdErrLogLevel(stdErrLogLevel)
            .setRobolectricLogPath(robolectricLogPath)
            .setExtraJvmArgs(properVmArgs)
            .addAllTestClasses(reorderedTestClasses)
            .setShouldExplainTestSelectorList(options.shouldExplainTestSelectorList())
            .setTestSelectorList(testSelectorList)
            .build();

    return new JUnitStep(
        getProjectFilesystem(),
        nativeLibsEnvironment,
        testRuleTimeoutMs,
        testCaseTimeoutMs,
        Arg.stringify(env, pathResolver),
        javaRuntimeLauncher.getCommandPrefix(pathResolver),
        args);
  }

  /** Returns the underlying java library containing the compiled tests. */
  public JavaLibrary getCompiledTestsLibrary() {
    return compiledTestsLibrary;
  }

  @SuppressWarnings("unused")
  protected void addPreTestSteps(
      BuildContext buildContext, ImmutableList.Builder<Step> stepsBuilder) {}

  /**
   * Runs the tests specified by the "srcs" of this class. If this rule transitively depends on
   * other {@code java_test()} rules, then they will be run separately.
   */
  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {

    // If no classes were generated, then this is probably a java_test() that declares a number of
    // other java_test() rules as deps, functioning as a test suite. In this case, simply return an
    // empty list of commands.
    Set<String> testClassNames = getClassNamesForSources(buildContext.getSourcePathResolver());
    LOG.debug("Testing these classes: %s", testClassNames.toString());
    if (testClassNames.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path pathToTestOutput = getPathToTestOutputDirectory();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), pathToTestOutput)));
    addPreTestSteps(buildContext, steps);
    if (forkMode() == ForkMode.PER_TEST) {
      ImmutableList.Builder<JUnitStep> junitsBuilder = ImmutableList.builder();
      for (String testClass : testClassNames) {
        junitsBuilder.add(
            getJUnitStep(
                executionContext,
                buildContext.getSourcePathResolver(),
                options,
                Optional.of(pathToTestOutput),
                Optional.of(pathToTestLogs),
                Collections.singleton(testClass)));
      }
      junits = junitsBuilder.build();
    } else {
      junits =
          ImmutableList.of(
              getJUnitStep(
                  executionContext,
                  buildContext.getSourcePathResolver(),
                  options,
                  Optional.of(pathToTestOutput),
                  Optional.of(pathToTestLogs),
                  testClassNames));
    }
    steps.addAll(junits);
    return steps.build();
  }

  private static Iterable<String> reorderClasses(Set<String> testClassNames, boolean shuffle) {
    Random rng;
    if (shuffle) {
      // This is a runtime-seed reorder, which always produces a new order.
      rng = new Random(System.nanoTime());
    } else {
      // This is fixed-seed reorder, which always produces the same order.
      // We still want to do this in order to decouple the test order from the
      // filesystem/environment.
      rng = new Random(TEST_CLASSES_SHUFFLE_SEED);
    }
    List<String> reorderedClassNames = Lists.newArrayList(testClassNames);
    Collections.shuffle(reorderedClassNames, rng);
    return reorderedClassNames;
  }

  ImmutableList<String> amendVmArgs(
      ImmutableList<String> existingVmArgs,
      SourcePathResolverAdapter pathResolver,
      Optional<TargetDevice> targetDevice,
      Optional<String> javaTempDir) {
    ImmutableList.Builder<String> vmArgs = ImmutableList.builder();
    vmArgs.addAll(existingVmArgs);
    javaTempDir.ifPresent(dir -> vmArgs.add(String.format("-Djava.io.tmpdir=%s", dir)));
    onAmendVmArgs(vmArgs, pathResolver, targetDevice);
    return vmArgs.build();
  }

  /**
   * Override this method if you need to amend vm args. Subclasses are required to call
   * super.onAmendVmArgs(...).
   */
  protected void onAmendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder,
      @SuppressWarnings("unused") SourcePathResolverAdapter pathResolver,
      Optional<TargetDevice> targetDevice) {
    if (!targetDevice.isPresent()) {
      return;
    }

    TargetDevice device = targetDevice.get();
    if (device.isEmulator()) {
      vmArgsBuilder.add("-Dbuck.device=emulator");
    } else {
      vmArgsBuilder.add("-Dbuck.device=device");
    }
    if (device.getIdentifier().isPresent()) {
      vmArgsBuilder.add("-Dbuck.device.id=" + device.getIdentifier().get());
    }
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__java_test_%s_output__");
  }

  /** @return a test case result, named "main", signifying a failure of the entire test class. */
  private TestCaseSummary getTestClassFailedSummary(String testClass, String message, long time) {
    return new TestCaseSummary(
        testClass,
        ImmutableList.of(
            new TestResultSummary(
                testClass, "main", ResultType.FAILURE, time, message, "", "", "")));
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext context,
      SourcePathResolverAdapter pathResolver,
      boolean isUsingTestSelectors) {
    ImmutableSet<String> contacts = getContacts();
    return () -> {
      // It is possible that this rule was not responsible for running any tests because all tests
      // were run by its deps. In this case, return an empty TestResults.
      Set<String> testClassNames = getClassNamesForSources(pathResolver);
      if (testClassNames.isEmpty()) {
        return TestResults.of(
            getBuildTarget(),
            ImmutableList.of(),
            contacts,
            labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
      }

      List<TestCaseSummary> summaries = Lists.newArrayListWithCapacity(testClassNames.size());
      for (String testClass : testClassNames) {
        String testSelectorSuffix = "";
        if (isUsingTestSelectors) {
          testSelectorSuffix += ".test_selectors";
        }
        String path = String.format("%s%s.xml", testClass, testSelectorSuffix);
        Path testResultFile =
            getProjectFilesystem()
                .getPathForRelativePath(getPathToTestOutputDirectory().resolve(path));
        if (!isUsingTestSelectors && !Files.isRegularFile(testResultFile)) {
          String message;
          for (JUnitStep junit : Objects.requireNonNull(junits)) {
            if (junit.hasTimedOut()) {
              message = "test timed out before generating results file";
            } else {
              message = "test exited before generating results file";
            }
            summaries.add(
                getTestClassFailedSummary(testClass, message, testRuleTimeoutMs.orElse(0L)));
          }
          // Not having a test result file at all (which only happens when we are using test
          // selectors) is interpreted as meaning a test didn't run at all, so we'll completely
          // ignore it.  This is another result of the fact that JUnit is the only thing that can
          // definitively say whether or not a class should be run.  It's not possible, for example,
          // to filter testClassNames here at the buck end.
        } else if (Files.isRegularFile(testResultFile)) {
          summaries.add(XmlTestResultParser.parse(testResultFile));
        }
      }

      return TestResults.builder()
          .setBuildTarget(getBuildTarget())
          .setTestCases(summaries)
          .setContacts(contacts)
          .setLabels(labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()))
          .addTestLogPaths(getProjectFilesystem().resolve(pathToTestLogs))
          .build();
    };
  }

  private Set<String> getClassNamesForSources(SourcePathResolverAdapter pathResolver) {
    if (compiledClassFileFinder == null) {
      compiledClassFileFinder = new CompiledClassFileFinder(compiledTestsLibrary, pathResolver);
    }
    return compiledClassFileFinder.getClassNamesForSources();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // Nothing to build, this is a test-only rule
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    SourcePath output = compiledTestsLibrary.getSourcePathToOutput();
    if (output == null) {
      return null;
    }
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return compiledTestsLibrary.getTransitiveClasspaths();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return compiledTestsLibrary.getTransitiveClasspathDeps();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return compiledTestsLibrary.getImmediateClasspaths();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return compiledTestsLibrary.getOutputClasspaths();
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return ImmutableSortedSet.of(compiledTestsLibrary);
  }

  @Override
  public SortedSet<BuildRule> getExportedProvidedDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public boolean runTestSeparately() {
    return runTestSeparately;
  }

  public ForkMode forkMode() {
    return forkMode;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
            // By the end of the build, all the transitive Java library dependencies *must* be
            // available on disk, so signal this requirement via the {@link HasRuntimeDeps}
            // interface.
            compiledTestsLibrary.getTransitiveClasspathDeps().stream(),
            // It's possible that the user added some tool as a dependency, so make sure we promote
            // this rules first-order deps to runtime deps, so that these potential tools are
            // available when this test runs.
            getBuildDeps().stream())
        .map(BuildRule::getBuildTarget);
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext, TestRunningOptions options, BuildContext buildContext) {
    externalJunitStep =
        getJUnitStep(
            executionContext,
            buildContext.getSourcePathResolver(),
            options,
            Optional.empty(),
            Optional.empty(),
            getClassNamesForSources(buildContext.getSourcePathResolver()));
    return ExternalTestRunnerTestSpec.builder()
        .setCwd(getProjectFilesystem().getRootPath().getPath())
        .setTarget(getBuildTarget())
        .setType("junit")
        .setCommand(externalJunitStep.getShellCommandInternal(executionContext))
        .setEnv(externalJunitStep.getEnvironmentVariables(executionContext))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .addAllRequiredPaths(getRuntimeClasspath(buildContext))
        .addRequiredPaths(externalJunitStep.getClasspathArgfile())
        .build();
  }

  @Override
  public void onPreTest(BuildContext buildContext) throws IOException {
    Preconditions.checkNotNull(externalJunitStep).ensureClasspathArgfile();
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext buildContext) {
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    getClassPathFile().getParent())))
        .add(
            new AbstractExecutionStep("write classpath file") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) throws IOException {
                ImmutableSet<Path> classpathEntries = getRuntimeClasspath(buildContext);
                getProjectFilesystem()
                    .writeLinesToPath(
                        Iterables.transform(classpathEntries, Object::toString),
                        getClassPathFile());
                return StepExecutionResults.SUCCESS;
              }
            })
        .build();
  }

  /**
   * @return a set of paths to the files which must be passed as the classpath to the java process
   *     when this test is executed
   */
  protected ImmutableSet<Path> getRuntimeClasspath(BuildContext buildContext) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    unbundledResourcesRoot.ifPresent(
        sourcePath ->
            builder.add(buildContext.getSourcePathResolver().getAbsolutePath(sourcePath)));
    return builder
        .addAll(
            compiledTestsLibrary.getTransitiveClasspaths().stream()
                .map(buildContext.getSourcePathResolver()::getAbsolutePath)
                .collect(ImmutableSet.toImmutableSet()))
        .addAll(
            additionalClasspathEntriesProvider
                .map(e -> e.getAdditionalClasspathEntries(buildContext.getSourcePathResolver()))
                .orElse(ImmutableList.of()))
        .addAll(getBootClasspathEntries())
        .build();
  }

  public interface AdditionalClasspathEntriesProvider {
    ImmutableList<Path> getAdditionalClasspathEntries(SourcePathResolverAdapter resolver);
  }
}
