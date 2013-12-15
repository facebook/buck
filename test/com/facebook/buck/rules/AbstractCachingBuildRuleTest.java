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

package com.facebook.buck.rules;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEvent;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class AbstractCachingBuildRuleTest extends EasyMockSupport {

  private static final BuildTarget buildTarget = new BuildTarget("//src/com/facebook/orca", "orca");

  @Rule
  public TemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * Tests what should happen when a rule is built for the first time: it should have no cached
   * RuleKey, nor should it have any artifact in the ArtifactCache. The sequence of events should be
   * as follows:
   * <ol>
   *   <li>The rule invokes the {@link BuildRule#build(BuildContext)} method of each of its deps.
   *   <li>The rule computes its own {@link RuleKey}.
   *   <li>It compares its {@link RuleKey} to the one on disk, if present.
   *   <li>Because the rule has no {@link RuleKey} on disk, the rule tries to build itself.
   *   <li>First, it checks the artifact cache, but there is a cache miss.
   *   <li>The rule generates its build steps and executes them.
   *   <li>Upon executing its steps successfully, it should write its {@link RuleKey} to disk.
   *   <li>It should persist its output to the ArtifactCache.
   * </ol>
   */
  @Test
  public void testBuildRuleLocallyWithCacheMiss()
      throws IOException, InterruptedException, ExecutionException, StepFailedException {
    // Create a dep for the build rule.
    BuildRule dep = createMock(BuildRule.class);
    expect(dep.isVisibleTo(buildTarget)).andReturn(true);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();

    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create an ArtifactCache whose expectations will be set later.
    ArtifactCache mockArtifactCache = createMock(ArtifactCache.class);

    ArtifactCache artifactCache = new LoggingArtifactCacheDecorator(buckEventBus)
        .decorate(mockArtifactCache);

    // Replay the mocks to instantiate the AbstractCachingBuildRule.
    replayAll();
    String pathToOutputFile = "buck-out/gen/src/com/facebook/orca/some_file";
    List<Step> buildSteps = Lists.newArrayList();
    AbstractCachingBuildRule cachingRule = createRule(
        ImmutableSet.of(dep),
        ImmutableList.of(Paths.get("/dev/null")),
        buildSteps,
        pathToOutputFile);
    verifyAll();
    resetAll();

    String expectedRuleKeyHash = Hashing.sha1().newHasher()
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("name".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes(cachingRule.getFullyQualifiedName().getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.type".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("java_library".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.inputs".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("ae8c0f860a0ecad94ecede79b69460434eddbfbc".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("deps".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("19d2558a6bd3a34fb3f95412de9da27ed32fe208".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .hash()
        .toString();

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(artifactCache).times(2);
    expect(context.getProjectRoot()).andReturn(createMock(Path.class));

    // Configure the OnDiskBuildInfo.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    expect(context.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);

    // Configure the BuildInfoRecorder.
    BuildInfoRecorder buildInfoRecorder = createMock(BuildInfoRecorder.class);
    Capture<RuleKey> ruleKeyForRecorder = new Capture<>();
    expect(
        context.createBuildInfoRecorder(
            eq(buildTarget),
            capture(ruleKeyForRecorder),
            /* ruleKeyWithoutDepsForRecorder */ capture(new Capture<RuleKey>())))
        .andReturn(buildInfoRecorder);
    expect(buildInfoRecorder.fetchArtifactForBuildable(
            capture(new Capture<File>()),
            eq(artifactCache)))
        .andReturn(CacheResult.MISS);

    // Set the requisite expectations to build the rule.
    expect(context.getExecutor()).andReturn(MoreExecutors.sameThreadExecutor());
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    context.logBuildInfo("[BUILDING %s]", "//src/com/facebook/orca:orca");
    StepRunner stepRunner = createMock(StepRunner.class);
    expect(context.getStepRunner()).andReturn(stepRunner);

    // The dependent rule will be built immediately with a distinct rule key.
    expect(dep.build(context)).andReturn(
        Futures.immediateFuture(new BuildRuleSuccess(dep, BuildRuleSuccess.Type.FETCHED_FROM_CACHE)));
    expect(dep.getRuleKey()).andReturn(new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208"));

    // Add a build step so we can verify that the steps are executed.
    Step buildStep = createMock(Step.class);
    buildSteps.add(buildStep);
    stepRunner.runStepForBuildTarget(buildStep, buildTarget);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.recordArtifact(Paths.get(pathToOutputFile));
    buildInfoRecorder.writeMetadataToDisk();
    buildInfoRecorder.performUploadToArtifactCache(artifactCache, buckEventBus);

    // Attempting to build the rule should force a rebuild due to a cache miss.
    replayAll();
    BuildRuleSuccess result = cachingRule.build(context).get();
    assertEquals(BuildRuleSuccess.Type.BUILT_LOCALLY, result.getType());
    verifyAll();

    assertEquals(expectedRuleKeyHash, ruleKeyForRecorder.getValue().toString());

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertEquals(configureTestEvent(BuildRuleEvent.started(cachingRule), buckEventBus),
        events.get(0));
    assertEquals(configureTestEvent(BuildRuleEvent.finished(cachingRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.MISS,
            Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
            buckEventBus),
        events.get(1));
  }

  @Test
  public void testDoNotFetchFromCacheIfDepBuiltLocally()
      throws ExecutionException, InterruptedException, IOException, StepFailedException {
    BuildRule dep1 = new FutureReturningFakeBuildRule(
        "//java/com/example:rule1",
        Strings.repeat("a", 40),
        BuildRuleSuccess.Type.BUILT_LOCALLY);
    BuildRule dep2 = new FutureReturningFakeBuildRule(
        "//java/com/example:rule2",
        Strings.repeat("b", 40),
        BuildRuleSuccess.Type.FETCHED_FROM_CACHE);

    final List<String> strings = Lists.newArrayList();
    Step buildStep = new AbstractExecutionStep("test_step") {
      @Override
      public int execute(ExecutionContext context) {
        strings.add("Step was executed.");
        return 0;
      }
    };
    AbstractCachingBuildRule cachingBuildRule = createRule(
        ImmutableSet.of(dep1, dep2),
        ImmutableList.of(Paths.get("/dev/null")),
        ImmutableList.of(buildStep),
        "buck-out/gen/src/com/facebook/orca/some_file");

    // Inject artifactCache to verify that its fetch method is never called.
    ArtifactCache artifactCache = new NoopArtifactCache() {
      @Override
      public CacheResult fetch(RuleKey ruleKey, File output) {
        throw new RuntimeException("Artifact cache must not be accessed while building the rule.");
      }
    };
    BuildContext buildContext = FakeBuildContext.newBuilder(new FakeProjectFilesystem())
        .setDependencyGraph(new DependencyGraph(new MutableDirectedGraph<BuildRule>()))
        .setJavaPackageFinder(new JavaPackageFinder() {
          @Override
          public String findJavaPackageFolderForPath(String pathRelativeToProjectRoot) {
            return null;
          }

          @Override
          public String findJavaPackageForPath(String pathRelativeToProjectRoot) {
            return null;
          }
        })
        .setArtifactCache(artifactCache)
        .build();

    BuildRuleSuccess result = cachingBuildRule.build(buildContext).get();
    assertEquals(result.getType(), BuildRuleSuccess.Type.BUILT_LOCALLY);
    MoreAsserts.assertListEquals(Lists.newArrayList("Step was executed."), strings);
  }

   /**
   * Rebuild a rule where one if its dependencies has been modified such that its RuleKey has
   * changed, but its ABI is the same.
   */
  @Test
  public void testAbiRuleCanAvoidRebuild()
      throws InterruptedException, ExecutionException, IOException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget);
    TestAbstractCachingBuildRule buildRule = new TestAbstractCachingBuildRule(buildRuleParams);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);

    BuildInfoRecorder buildInfoRecorder = createMock(BuildInfoRecorder.class);
    expect(buildContext.createBuildInfoRecorder(
           eq(buildTarget),
           /* ruleKey */ capture(new Capture<RuleKey>()),
           /* ruleKeyWithoutDeps */ capture(new Capture<RuleKey>())))
        .andReturn(buildInfoRecorder);

    // Populate the metadata that should be read from disk.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo()
         // The RuleKey on disk should be different from the current RuleKey in memory, so reverse()
         // it.
         .setRuleKey(reverse(buildRule.getRuleKey()))
         // However, the RuleKey not including the deps in memory should be the same as the one on
         // disk.
         .setRuleKeyWithoutDeps(new RuleKey(TestAbstractCachingBuildRule.RULE_KEY_WITHOUT_DEPS_HASH))
         // Similarly, the ABI key for the deps in memory should be the same as the one on disk.
        .putMetadata(AbiRule.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
            TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH)
        .putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA,
            "At some point, this method call should go away.");

    // This metadata must be added to the buildInfoRecorder so that it is written as part of
    // writeMetadataToDisk().
    buildInfoRecorder.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA,
        "At some point, this method call should go away.");
    buildInfoRecorder.addMetadata(AbiRule.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
        TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.writeMetadataToDisk();

    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getExecutor()).andReturn(MoreExecutors.sameThreadExecutor());
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();

    ListenableFuture<BuildRuleSuccess> result = buildRule.build(buildContext);
    assertTrue("We expect build() to be synchronous in this case, " +
    		       "so the future should already be resolved.",
               MoreFutures.isSuccess(result));

    BuildRuleSuccess success = result.get();
    assertEquals(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS, success.getType());
    assertTrue(buildRule.isAbiLoadedFromDisk());

    List<BuckEvent> events = listener.getEvents();
    assertEquals(events.get(0),
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus));
    assertEquals(events.get(1),
        configureTestEvent(BuildRuleEvent.finished(buildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.LOCAL_KEY_UNCHANGED_HIT,
            Optional.of(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS)),
            buckEventBus));

    verifyAll();
  }

  @Test
  public void testArtifactFetchedFromCache()
      throws InterruptedException, ExecutionException, IOException {
    Step step = new AbstractExecutionStep("exploding step") {
      @Override
      public int execute(ExecutionContext context) {
        throw new UnsupportedOperationException("build step should not be executed");
      }
    };
    BuildableAbstractCachingBuildRule cachingRule = createRule(
        /* deps */ ImmutableSet.<BuildRule>of(),
        ImmutableList.<Path>of(),
        ImmutableList.of(step),
        /* pathToOutputFile */ null);

    StepRunner stepRunner = createMock(StepRunner.class);
    expect(stepRunner.getListeningExecutorService()).andReturn(MoreExecutors.sameThreadExecutor());

    // Mock out all of the disk I/O.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem
        .readFileIfItExists(
            Paths.get("buck-out/bin/src/com/facebook/orca/.orca/metadata/RULE_KEY")))
        .andReturn(Optional.<String>absent());
    expect(projectFilesystem.getRootPath()).andReturn(tmp.getRoot().toPath());

    // Simulate successfully fetching the output file from the ArtifactCache.
    ArtifactCache artifactCache = createMock(ArtifactCache.class);
    Map<String, String> desiredZipEntries = ImmutableMap.of(
        "buck-out/gen/src/com/facebook/orca/orca.jar",
        "Imagine this is the contents of a valid JAR file."
        );
    expect(
        artifactCache.fetch(
            eq(cachingRule.getRuleKey()),
            capture(new CaptureThatWritesAZipFile(desiredZipEntries))))
        .andReturn(CacheResult.DIR_HIT);

    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(RuleMap.createGraphFromSingleRule(cachingRule))
        .setStepRunner(stepRunner)
        .setProjectFilesystem(projectFilesystem)
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(BuckEventBusFactory.newInstance())
        .build();

    // Build the rule!
    replayAll();
    ListenableFuture<BuildRuleSuccess> result = cachingRule.build(buildContext);
    verifyAll();

    assertTrue("We expect build() to be synchronous in this case, " +
        "so the future should already be resolved.",
        MoreFutures.isSuccess(result));
    BuildRuleSuccess success = result.get();
    assertEquals(BuildRuleSuccess.Type.FETCHED_FROM_CACHE, success.getType());
    assertTrue(cachingRule.isInitializedFromDisk());
    assertTrue(
        "The entries in the zip should be extracted as a result of building the rule.",
        new File(tmp.getRoot(), "buck-out/gen/src/com/facebook/orca/orca.jar").isFile());
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is written
  // back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private static BuildableAbstractCachingBuildRule createRule(
      ImmutableSet<BuildRule> deps,
      Iterable<Path> inputs,
      List<Step> buildSteps,
      @Nullable String pathToOutputFile) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    final FileHashCache fileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
          "/dev/null", "ae8c0f860a0ecad94ecede79b69460434eddbfbc"));
    final RuleKeyBuilderFactory ruleKeyBuilderFactory = new RuleKeyBuilderFactory() {
      @Override
      public RuleKey.Builder newInstance(BuildRule buildRule) {
        return RuleKey.builder(buildRule, fileHashCache);
      }
    };
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget, sortedDeps) {
      @Override
      public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
        return ruleKeyBuilderFactory;
      }
    };

    return new BuildableAbstractCachingBuildRule(buildRuleParams,
        inputs,
        pathToOutputFile,
        buildSteps);
  }

  private static class FutureReturningFakeBuildRule extends FakeBuildRule {

    private final BuildRuleSuccess ruleSuccess;

    public FutureReturningFakeBuildRule(String buildTarget,
        String ruleKeyHash,
        BuildRuleSuccess.Type successType) {
      super(BuildRuleType.ANDROID_RESOURCE, BuildTargetFactory.newInstance(buildTarget));
      super.setRuleKey(new RuleKey(ruleKeyHash));
      this.ruleSuccess = new BuildRuleSuccess(this, successType);
    }

    @Override
    public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
      return Futures.immediateFuture(ruleSuccess);
    }
  }

  private static class BuildableAbstractCachingBuildRule extends DoNotUseAbstractBuildable
      implements InitializableFromDisk<Object> {

    private final Iterable<Path> inputs;
    private final String pathToOutputFile;
    private final List<Step> buildSteps;

    @Nullable
    private Object buildOutput;
    private boolean isInitializedFromDisk = false;

    private BuildableAbstractCachingBuildRule(BuildRuleParams params,
        Iterable<Path> inputs,
        @Nullable String pathToOutputFile,
        List<Step> buildSteps) {
      super(params);
      this.inputs = inputs;
      this.pathToOutputFile = pathToOutputFile;
      this.buildSteps = buildSteps;
    }

    @Override
    public BuildRuleType getType() {
      return BuildRuleType.JAVA_LIBRARY;
    }

    @Override
    public Iterable<Path> getInputs() {
      return inputs;
    }

    @Override
    @Nullable
    public String getPathToOutputFile() {
      return pathToOutputFile;
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
        throws IOException {
      return buildSteps;
    }

    @Override
    public Iterable<String> getInputsToCompareToOutput() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
      isInitializedFromDisk = true;
      return new Object();
    }

    @Override
    public void setBuildOutput(Object buildOutput) {
      if (this.buildOutput != null) {
        throw new IllegalStateException("buildOutput is already set for " + this);
      }
      this.buildOutput = buildOutput;
    }

    @Override
    public Object getBuildOutput() {
      if (buildOutput == null) {
        throw new IllegalStateException("buildOutput has not been set for " + this);
      }
      return buildOutput;
    }

    public boolean isInitializedFromDisk() {
      return isInitializedFromDisk;
    }
  }

  /**
   * {@link AbstractCachingBuildRule} that implements {@link AbiRule}.
   */
  private static class TestAbstractCachingBuildRule extends DoNotUseAbstractBuildable
      implements AbiRule, Buildable, InitializableFromDisk<Object> {

    private static final String RULE_KEY_HASH = "bfcd53a794e7c732019e04e08b30b32e26e19d50";
    private static final String RULE_KEY_WITHOUT_DEPS_HASH =
        "efd7d450d9f1c3d9e43392dec63b1f31692305b9";
    private static final String ABI_KEY_FOR_DEPS_HASH = "92d6de0a59080284055bcde5d2923f144b216a59";

    @Nullable
    private Object buildOutput;
    private boolean isAbiLoadedFromDisk = false;

    TestAbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
      super(buildRuleParams);
    }

    @Override
    public Iterable<String> getInputsToCompareToOutput() {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
        throws IOException {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public BuildRuleType getType() {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public RuleKey getRuleKey() {
      return new RuleKey(RULE_KEY_HASH);
    }

    @Override
    public RuleKey getRuleKeyWithoutDeps() {
      return new RuleKey(RULE_KEY_WITHOUT_DEPS_HASH);
    }

    @Override
    public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
      isAbiLoadedFromDisk = true;
      return new Object();
    }

    @Override
    public void setBuildOutput(Object buildOutput) {
      if (this.buildOutput != null) {
        throw new IllegalStateException("buildOutput is already set for " + this);
      }
      this.buildOutput = buildOutput;
    }

    @Override
    public Object getBuildOutput() {
      if (buildOutput == null) {
        throw new IllegalStateException("buildOutput has not been set for " + this);
      }
      return buildOutput;
    }

    public boolean isAbiLoadedFromDisk() {
      return isAbiLoadedFromDisk;
    }

    @Override
    public Sha1HashCode getAbiKeyForDeps() {
      return new Sha1HashCode(ABI_KEY_FOR_DEPS_HASH);
    }
  }

  /**
   * Subclass of {@link Capture} that, when its {@link File} value is set, takes the location of
   * that {@link File} and writes a zip file there with the entries specified to the constructor of
   * {@link CaptureThatWritesAZipFile}.
   * <p>
   * This makes it possible to capture a call to {@link ArtifactCache#store(RuleKey, File)} and
   * ensure that there will be a zip file in place immediately after the captured method has been
   * invoked.
   */
  @SuppressWarnings("serial")
  private static class CaptureThatWritesAZipFile extends Capture<File> {

    private final Map<String, String> desiredEntries;

    public CaptureThatWritesAZipFile(Map<String, String> desiredEntries) {
      this.desiredEntries = ImmutableMap.copyOf(desiredEntries);
    }

    @Override
    public void setValue(File file) {
      super.setValue(file);

      // This must have the side-effect of writing a zip file in the specified place.
      try {
        writeEntries(file);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private void writeEntries(File file) throws IOException {
      try (ZipOutputStream zip = new ZipOutputStream(
          new BufferedOutputStream(
              new FileOutputStream(file)))) {
        for (Map.Entry<String, String> mapEntry : desiredEntries.entrySet()) {
          ZipEntry entry = new ZipEntry(mapEntry.getKey());
          zip.putNextEntry(entry);
          zip.write(mapEntry.getValue().getBytes());
          zip.closeEntry();
        }
      }
    }
  }

  /**
   * @return a RuleKey with the bits of the hash in reverse order, just to be different.
   */
  private static RuleKey reverse(RuleKey ruleKey) {
    String hash = ruleKey.getHashCode().toString();
    String reverseHash = new StringBuilder(hash).reverse().toString();
    return new RuleKey(reverseHash);
  }
}
