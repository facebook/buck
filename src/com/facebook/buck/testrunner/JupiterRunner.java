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

package com.facebook.buck.testrunner;

import static com.facebook.buck.testrunner.JUnitSupport.junit4Api;
import static com.facebook.buck.testrunner.JUnitSupport.jupiterApi;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelector;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.JupiterTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestFactoryTestDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestExecutionResult.Status;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.UniqueId.Segment;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Class that runs a set of JUnit tests, (supports JUnit4 and JUnit5 tests). The result of each test
 * class is saved to the specified directory.
 *
 * <p>The intent of this class is to be backward compatible with JUnit4, as also maintain the same
 * report format that buck does use. Once the confidence that all the projects does run perfectly
 * with this new runner, the JUnitRunner should be replaced by this.
 *
 * @see JUnitRunner
 */
public final class JupiterRunner extends BaseRunner {

  public static final String EXTENSIONS_AUTODETECTION_ENABLED =
      "junit.jupiter.extensions.autodetection.enabled";

  private final Launcher launcher;

  JupiterRunner() {
    this(LauncherFactory.create());
  }

  JupiterRunner(Launcher launcher) {
    this.launcher = launcher;
  }

  JUnitOptions getOptions() {
    return JUnitOptions.builder()
        .testSelectorList(testSelectorList)
        .dryRun(isDryRun)
        .defaultTestTimeoutMillis(defaultTestTimeoutMillis)
        .shouldExplainTestSelectors(shouldExplainTestSelectors)
        .build();
  }

  @Override
  public void run() throws Throwable {
    JUnitOptions options = getOptions();
    for (String className : testClassNames) {
      writeResult(className, runTest(options, Class.forName(className)));
    }
  }

  private List<TestResult> runTest(JUnitOptions options, Class<?> testClass) {

    TestAnalyzer testAnalyzer = new TestAnalyzer();
    RecordingListener recordingListener = new RecordingListener(options, testAnalyzer);
    RecordingFilter recordingFilter =
        new RecordingFilter(options, testAnalyzer)
            // Register this filter as a SkipSelector for methods
            .registerAsSkipFilter();

    if (testAnalyzer.isEnabled(testClass)) {
      LauncherDiscoveryRequest request = testRequest(testClass, recordingFilter);
      launcher.execute(request, new TestExecutionListener[] {recordingListener});
    }
    // Combine the results with the tests we filtered out
    return combineResults(recordingListener.getTestResults(), recordingFilter.getFilteredOut());
  }

  private LauncherDiscoveryRequest testRequest(
      Class<?> testClass, org.junit.platform.engine.Filter<?> filter) {
    return LauncherDiscoveryRequestBuilder.request()
        .filters(filter)
        .selectors(DiscoverySelectors.selectClass(testClass))
        // enable discovery of SkipTestCondition
        .configurationParameter(EXTENSIONS_AUTODETECTION_ENABLED, Boolean.TRUE.toString())
        .build();
  }

  /**
   * This method filters a list of test results prior to writing results to a file. null is returned
   * to indicate "don't write anything", which is different to writing a file containing 0 results.
   *
   * <p>JUnit handles classes-without-tests in different ways. If you are not using the
   * org.junit.runner.Request.filterWith facility then JUnit ignores classes-without-tests. However,
   * if you are using a filter then a class-without-tests will cause a NoTestsRemainException to be
   * thrown, which is propagated back as an error.
   */
  List<TestResult> combineResults(List<TestResult> results, List<TestResult> filteredResults) {
    if (!isSingleResultCausedByNoTestsRemainException(results)) {
      List<TestResult> combined = new ArrayList<>(filteredResults.size() + results.size());
      combined.addAll(filteredResults);
      combined.addAll(results);
      return combined;
    }
    return new ArrayList<>(filteredResults);
  }

  /**
   * JUnit doesn't normally consider encountering a testless class an error. However, when using
   * org.junit.runner.manipulation.Filter, testless classes *are* considered an error, throwing
   * org.junit.runner.manipulation.NoTestsRemainException.
   *
   * <p>If we are using test-selectors then it's possible we will run a test class but never run any
   * of its test methods, because they'd all get filtered out. When this happens, the results will
   * contain a single failure containing the error from the NoTestsRemainException.
   *
   * <p>However, there is another reason why the test class may have a single failure -- if the
   * class fails to instantiate, then it doesn't get far enough to detect whether or not there were
   * any tests. In that case, JUnit4 returns a single failure result with the testMethodName set to
   * "initializationError".
   *
   * <p>(NB: we can't decide at the class level whether we need to run a test class or not; we can
   * only run the test class and all its test methods and handle the erroneous exception JUnit
   * throws if no test-methods were actually run.)
   */
  private boolean isSingleResultCausedByNoTestsRemainException(List<TestResult> results) {
    if (results.size() != 1) {
      return false;
    }
    TestResult singleResult = results.get(0);
    return !singleResult.isSuccess()
        && "initializationError".equals(singleResult.testMethodName)
        && "org.junit.runner.manipulation.Filter".equals(singleResult.testClassName);
  }

  /**
   * Filter to verify if a {@code TestSelector} is provided and record results for disabled or tests
   * that were excluded.
   *
   * <p>Implementation based on the JUnitRunner
   *
   * @see TestSelector
   */
  static class RecordingFilter implements PostDiscoveryFilter {

    private final JUnitOptions options;
    private final TestAnalyzer testAnalyzer;
    private final List<TestResult> filteredOut = new ArrayList<>();

    RecordingFilter(JUnitOptions options, TestAnalyzer testAnalyzer) {
      this.options = options;
      this.testAnalyzer = testAnalyzer;
    }

    public RecordingFilter registerAsSkipFilter() {
      SkipTestCondition.useSkipFilter(this::shouldSkip);
      return this;
    }

    public List<TestResult> getFilteredOut() {
      return filteredOut;
    }

    @Override
    public FilterResult apply(TestDescriptor test) {
      // validate current node
      if (shouldExecute(test)) {
        // apply nested filter to lazy dynamic execution
        applyLazyFilter(test);
        return FilterResult.included(null);
      }
      // return only one JUnit4 JUnitParams
      testAnalyzer.clearJUnitParamsNestedRunners(test);
      return FilterResult.excluded(null);
    }

    /**
     * TestFactory methods behave differently from Parametrized and uses a internal
     * DynamicTestFilter, so we have to wrap it to collect dry-run and other cases.
     *
     * @see FilterableTestFactory
     */
    private void applyLazyFilter(TestDescriptor parent) {
      // lookup for nested @TestFactory containers to apply lazy-filter evaluation
      parent.getChildren().stream()
          // look for DynamicTest factory
          .filter(child -> child instanceof TestFactoryTestDescriptor)
          .map(TestFactoryTestDescriptor.class::cast)
          // collect so we can modify the list at the parent
          .collect(Collectors.toList())
          .forEach(
              test -> {
                parent.removeChild(test);
                parent.addChild(FilterableTestFactory.to(test, this::shouldExecute));
              });
    }

    boolean shouldExecute(TestDescriptor test) {
      return testAnalyzer
          .getDescription(test)
          .map(
              desc -> {
                // Ignore if the entire class is disabled
                if (testAnalyzer.isClassDisabled(test.getSource())) {
                  return false;
                }
                // @ParametrizedTest and @TestFactory will be skipped by the SkipSelector
                if (excludedBySelector(desc)) {
                  // to keep compatibility with junit4 runner
                  return false;
                }
                if (testAnalyzer.isDisabled(test.getSource())) {
                  disable(desc);
                  return false;
                }
                if (options.isDryRun()) {
                  dryRun(desc);
                  return false;
                }
                return true;
              })
          // do not exclude empty descriptors, that might be test containers
          .orElse(true);
    }

    boolean shouldSkip(ExtensionContext context) {
      return testAnalyzer.getDescription(context).map(this::shouldSkip).orElse(false);
    }

    private boolean shouldSkip(TestDescription desc) {
      if (excludedBySelector(desc)) {
        return true;
      }
      if (options.isDryRun()) {
        dryRun(desc);
        return true;
      }
      return false;
    }

    private boolean excludedBySelector(TestDescription desc) {
      TestSelector selector = options.getTestSelectorList().findSelector(desc);
      if (selector.isInclusive()) {
        return false;
      }
      exclude(desc, selector.getExplanation());
      return true;
    }

    private void exclude(TestDescription desc, String reason) {
      if (options.isShouldExplainTestSelectors()) {
        filteredOut.add(
            TestResult.forExcluded(
                desc.getClassName(), desc.getMethodName(), "Excluded by filter: " + reason));
      }
    }

    private void dryRun(TestDescription desc) {
      filteredOut.add(TestResult.forDryRun(desc.getClassName(), desc.getMethodName()));
    }

    private void disable(TestDescription desc) {
      filteredOut.add(TestResult.forDisabled(desc.getClassName(), desc.getMethodName()));
    }
  }

  /** Wrapper to do lazy evaluation in DynamicTests (@TestFactory methods). */
  static class FilterableTestFactory extends TestFactoryTestDescriptor {

    private static final Field CONFIGURATION = configurationField();

    static Field configurationField() {
      try {
        return ReflectionUtils.makeAccessible(
            JupiterTestDescriptor.class.getDeclaredField("configuration"));
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException("failed to get configuration field", e);
      }
    }

    static FilterableTestFactory to(
        TestFactoryTestDescriptor test, Predicate<TestDescriptor> filter) {
      JupiterConfiguration configuration =
          ReflectionUtils.tryToReadFieldValue(CONFIGURATION, test)
              .toOptional()
              .map(JupiterConfiguration.class::cast)
              .orElse(null);
      return new FilterableTestFactory(test, configuration, filter);
    }

    private final Predicate<TestDescriptor> filter;

    FilterableTestFactory(
        TestFactoryTestDescriptor test,
        JupiterConfiguration configuration,
        Predicate<TestDescriptor> filter) {
      super(test.getUniqueId(), test.getTestClass(), test.getTestMethod(), configuration);
      this.filter = filter;
    }

    @Override
    protected void invokeTestMethod(
        JupiterEngineExecutionContext context, DynamicTestExecutor dynamicTestExecutor) {
      super.invokeTestMethod(
          context,
          new DynamicTestExecutor() {
            @Override
            public void execute(TestDescriptor test) {
              if (filter.test(test)) {
                dynamicTestExecutor.execute(test);
              }
            }

            @Override
            public Future<?> execute(
                TestDescriptor test, EngineExecutionListener executionListener) {
              if (filter.test(test)) {
                return dynamicTestExecutor.execute(test, executionListener);
              }
              return CompletableFuture.completedFuture(null);
            }

            @Override
            public void awaitFinished() throws InterruptedException {
              dynamicTestExecutor.awaitFinished();
            }
          });
    }
  }

  /**
   * Listener responsible for record all test executions.
   *
   * <p>Implementation based on the JUnitRunner
   *
   * @see TestResult
   */
  static class RecordingListener implements TestExecutionListener {

    private final JUnitOptions options;
    private final TestAnalyzer testAnalyzer;
    private final List<TestResult> testResults = new ArrayList<>();
    private final long startTime = System.currentTimeMillis();

    private TestRecorder testRecorder;

    public RecordingListener(JUnitOptions options, TestAnalyzer testAnalyzer) {
      this.options = options;
      this.testAnalyzer = testAnalyzer;
    }

    public List<TestResult> getTestResults() {
      return testResults;
    }

    @Override
    public void executionStarted(TestIdentifier test) {
      if (test.isTest()) {
        // record only leaf/tests
        testRecorder = new TestRecorder(options).record();
      }
    }

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
      if (testRecorder != null) {
        recordResult(test, result, testRecorder.complete());
        testRecorder = null;
      } else if (!test.isTest()) {
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
          recordUnpairedResult(test, result.getThrowable());
        }
      }
    }

    private void recordResult(
        TestIdentifier test, TestExecutionResult result, TestRecorder output) {
      testAnalyzer
          .getDescription(test)
          .ifPresent(description -> recordResult(description, result, output));
    }

    private void recordResult(
        TestDescription description, TestExecutionResult result, TestRecorder output) {

      ResultType type = getResultType(result.getStatus(), result.getThrowable());
      boolean shouldAppendLog = (type != ResultType.SUCCESS);

      testResults.add(
          new TestResult(
              description.getClassName(),
              description.getMethodName(),
              output.getDuration(),
              type,
              result.getThrowable().orElse(null),
              output.standardOutputAsString(shouldAppendLog),
              output.standardErrorAsString(shouldAppendLog)));
    }

    /**
     * It's possible to encounter a Failure/Skip before we've started any tests (and therefore
     * before testStarted() has been called). The known example is a @BeforeClass that throws an
     * exception, but there may be others.
     *
     * <p>Recording these unexpected failures helps us propagate failures back up to the "buck test"
     * process.
     */
    private void recordUnpairedResult(TestIdentifier test, Optional<Throwable> failure) {
      testAnalyzer
          .getDescription(test)
          .ifPresent(description -> recordUnpairedResult(description, failure));
    }

    private void recordUnpairedResult(TestDescription description, Optional<Throwable> failure) {
      testResults.add(
          new TestResult(
              description.getClassName(),
              description.getMethodName(),
              System.currentTimeMillis() - startTime,
              getResultType(null, failure),
              failure.orElse(null),
              null,
              null));
    }

    private ResultType getResultType(Status status, Optional<Throwable> failure) {
      if (status == TestExecutionResult.Status.SUCCESSFUL) {
        return ResultType.SUCCESS;
      }
      return junit4Api().isAssumption(failure.orElse(null))
          ? ResultType.ASSUMPTION_VIOLATION
          : ResultType.FAILURE;
    }
  }

  /**
   * Normalize several all elements from the JUnit graph into a simple TestDescriptor. Important to
   * keep compatibility with <code>test_type=junit</code> report.
   */
  static class TestAnalyzer {

    Optional<TestDescription> getDescription(ExtensionContext context) {
      return ReflectionUtils.findMethod(context.getClass(), "getTestDescriptor")
          .map(method -> ReflectionUtils.invokeMethod(method, context))
          .map(TestDescriptor.class::cast)
          .flatMap(this::getDescription);
    }

    Optional<TestDescription> getDescription(TestIdentifier test) {
      String id = test.getUniqueId();
      String displayName = test.getDisplayName();
      return getClassName(test.getSource())
          .map(
              className -> {
                String methodName = getMethodNameWithParams(id, displayName, test.getSource());
                return new TestDescription(className, methodName);
              });
    }

    Optional<TestDescription> getDescription(TestDescriptor test) {
      // tests are leafs, no children
      String id = test.getUniqueId().toString();
      String displayName = test.getDisplayName();
      Optional<TestSource> source = test.getSource();
      // For vintage "pl.pragmatists.JUnitParams", we return the parent descriptor (method)
      // to preserve same behavior of test_type="junit"
      Optional<TestDescription> junitParamsDescription = getJUnitParamsTestDescription(test);
      if (junitParamsDescription.isPresent()) {
        return junitParamsDescription;
      }
      // @TestFactory will not be marked as type=test
      if (test.isTest()) {
        return getClassName(source)
            .map(
                className -> {
                  String methodName = getMethodNameWithParams(id, displayName, source);
                  return new TestDescription(className, methodName);
                });
      }
      return Optional.empty();
    }

    /**
     * When running Vintage "pl.pragmatists.JUnitParams", we have to emulate the same behavior of
     * <code>buck test --dry-run</code> for test_type="junit" which it returns the parent method
     * name and not each individual parameters that will be used
     */
    void clearJUnitParamsNestedRunners(TestDescriptor test) {
      getJUnitParamsTestDescription(test)
          .ifPresent(
              desc ->
                  test.getChildren().stream()
                      .collect(Collectors.toList())
                      .forEach(test::removeChild));
    }

    private Optional<TestDescription> getJUnitParamsTestDescription(TestDescriptor test) {
      if (!isJUnitParamsMethod(test)) {
        return Optional.empty();
      }
      Optional<String> runnerName = getSegmentValue(test, "runner");
      Optional<String> testName = getSegmentValue(test, "test");
      if (runnerName.isPresent() && testName.isPresent()) {
        return Optional.of(new TestDescription(runnerName.get(), testName.get()));
      }
      return Optional.empty();
    }

    private Optional<String> getSegmentValue(TestDescriptor test, String type) {
      return test.getUniqueId().getSegments().stream()
          .filter(segment -> type.equals(segment.getType()))
          .map(Segment::getValue)
          .findFirst();
    }

    private Optional<String> getClassName(Optional<TestSource> source) {
      TestSource testSource = source.orElse(null);
      if (testSource instanceof MethodSource) {
        return Optional.ofNullable(((MethodSource) testSource).getClassName());
      } else if (testSource instanceof ClassSource) {
        return Optional.ofNullable(((ClassSource) testSource).getClassName());
      }
      return Optional.empty();
    }

    String getMethodNameWithParams(String id, String displayName, Optional<TestSource> source) {
      if (isVintageEngine(id)) {
        return displayName;
      }
      // JUnit5 has paramTypes in the name
      String name = getTestSourceName(displayName, source);
      // displayName will be the parameter values
      if (isDynamicOrParameterized(id)) {
        return name + "[" + displayName + "]";
      }
      return name;
    }

    private String getTestSourceName(String displayName, Optional<TestSource> testSource) {
      TestSource source = testSource.orElse(null);
      if (source instanceof MethodSource) {
        MethodSource methodSource = (MethodSource) source;
        String name = methodSource.getMethodName();
        String paramTypes = methodSource.getMethodParameterTypes();
        if (paramTypes == null || paramTypes.isEmpty()) {
          return name;
        }
        return name + "(" + paramTypes.replaceAll(" ", "") + ")";
      }
      return displayName;
    }

    private boolean isVintageEngine(UniqueId id) {
      return isVintageEngine(id.toString());
    }

    /**
     * @param id Test id
     * @return if the test engine is from the Vintage JUnit4 Engine
     */
    private boolean isVintageEngine(String id) {
      return id.contains("[engine:junit-vintage]");
    }

    /**
     * @param id Test id
     * @return if the test is a ParametrizedTest or from a TestFactory
     */
    private boolean isDynamicOrParameterized(String id) {
      // junixsocket-selftest-2.4.0-jar-with-dependencies that is packing and old version
      // because of that we can't use the identifier.getUniqueIdObject().getLastSegment().getType()
      // dynamic-test: tests produced by @TestFactory
      return id.contains("[dynamic-test:")
          // test-template-invocation: tests produced by @ParametrizedTest
          || id.contains("[test-template-invocation:");
    }

    private boolean isJUnitParamsMethod(TestDescriptor test) {
      UniqueId uniqueId = test.getUniqueId();
      if (!isVintageEngine(uniqueId)) {
        return false;
      }
      boolean hasNoSource = !test.getSource().isPresent();
      boolean hasJunitParamsTest =
          !test.getChildren().isEmpty()
              && test.getChildren().stream().allMatch(this::isJunitParamsTest);
      return hasNoSource && hasJunitParamsTest;
    }

    private boolean isJunitParamsTest(TestDescriptor test) {
      if (test.isTest()) {
        // JUnitParams will have children with ClassSource only
        // ParametrizedTests will be MethodSource
        return test.getChildren().isEmpty()
            && test.getSource().filter(source -> source instanceof ClassSource).isPresent();
      }
      return false;
    }

    public boolean isEnabled(Class<?> testClass) {
      return isTest(testClass) && !isDisabled(testClass);
    }

    private boolean isClassDisabled(Optional<TestSource> source) {
      return source.map(this::isClassDisabled).orElse(false);
    }

    private boolean isDisabled(Optional<TestSource> source) {
      return source.map(this::isDisabled).orElse(false);
    }

    private boolean isClassDisabled(TestSource source) {
      if (source instanceof MethodSource) {
        return isDisabled(((MethodSource) source).getJavaClass());
      }
      if (source instanceof ClassSource) {
        return isDisabled(((ClassSource) source).getJavaClass());
      }
      return false;
    }

    private boolean isDisabled(TestSource source) {
      if (isClassDisabled(source)) {
        return true;
      }
      if (source instanceof MethodSource) {
        return isDisabled(((MethodSource) source).getJavaMethod());
      }
      return false;
    }

    private boolean isDisabled(AnnotatedElement element) {
      return jupiterApi().disabled(element) || junit4Api().disabled(element);
    }

    private boolean isTest(Class<?> testClass) {
      return jupiterApi().test(testClass) || junit4Api().test(testClass);
    }
  }
}
