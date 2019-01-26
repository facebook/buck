/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.build.engine.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.InMemoryArtifactCache;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizer;
import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizerTestUtil;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngineTest.CommonFixture;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.build.engine.type.MetadataStorage;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runners.Parameterized;

public class CachingBuildEngineInitializableFromDiskTest extends CommonFixture {
  public static final boolean DEBUG = false;

  private static final String DEPFILE_INPUT_CONTENT = "depfile input";
  private static final String NON_DEPFILE_INPUT_CONTENT = "depfile input";
  public static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//src/com/facebook/buck:buck");

  private PathSourcePath depfileInput;
  private PathSourcePath nonDepfileInput;
  private SimpleNoopRule rootRule;
  private InitializableFromDiskRule dependency;
  private InitializableFromDiskRule buildRule;
  private InitializableFromDiskRule dependent;
  private ArtifactCache artifactCache;
  private BuildRuleSuccessType lastSuccessType;

  private final PipelineType pipelineType;

  enum PipelineType {
    NONE,
    BEGINNING,
    MIDDLE
  }

  @Parameterized.Parameters(name = "{0}-{1}")
  public static Collection<Object[]> data() {
    return Streams.concat(
            Arrays.stream(MetadataStorage.values()).map(v -> new Object[] {v, PipelineType.NONE}),
            Stream.of(
                new Object[] {MetadataStorage.SQLITE, PipelineType.BEGINNING},
                new Object[] {MetadataStorage.SQLITE, PipelineType.MIDDLE}))
        .collect(ImmutableList.toImmutableList());
  }

  public CachingBuildEngineInitializableFromDiskTest(
      MetadataStorage metadataStorage, PipelineType pipelineType) {
    super(metadataStorage);
    this.pipelineType = pipelineType;
  }

  private static class SimpleNoopRule extends NoopBuildRule {
    @AddToRuleKey private int value = 0;

    public SimpleNoopRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
      super(buildTarget, projectFilesystem);
    }
  }

  private static class InitializableFromDiskRule
      extends CachingBuildEngineTest.AbstractCachingBuildRuleWithInputs
      implements SupportsPipelining<SimplePipelineState> {

    @AddToRuleKey private final PipelineType pipelineType;

    private final BuildRule dependency;
    private final RulePipelineStateFactory<SimplePipelineState> pipelineStateFactory;

    public InitializableFromDiskRule(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        BuildRule dependency,
        ImmutableSortedSet<SourcePath> inputs,
        ImmutableSortedSet<SourcePath> depfileInputs,
        RulePipelineStateFactory<SimplePipelineState> pipelineStateFactory,
        PipelineType pipelineType) {
      super(
          buildTarget,
          filesystem,
          null,
          ImmutableList.of(),
          ImmutableList.of(),
          ImmutableSortedSet.of(dependency),
          inputs,
          depfileInputs);

      this.dependency = dependency;
      this.pipelineStateFactory = pipelineStateFactory;
      this.pipelineType = pipelineType;
    }

    @Override
    public boolean useRulePipelining() {
      return pipelineType != PipelineType.NONE;
    }

    @Nullable
    @Override
    public SupportsPipelining<SimplePipelineState> getPreviousRuleInPipeline() {
      return pipelineType == PipelineType.MIDDLE && dependency instanceof SupportsPipelining
          ? (SupportsPipelining<SimplePipelineState>) dependency
          : null;
    }

    @Override
    public ImmutableList<? extends Step> getPipelinedBuildSteps(
        BuildContext context, BuildableContext buildableContext, SimplePipelineState state) {
      return ImmutableList.of();
    }

    @Override
    public RulePipelineStateFactory<SimplePipelineState> getPipelineStateFactory() {
      return pipelineStateFactory;
    }
  }

  @Before
  public void setUpChild() throws Exception {
    depfileInput = FakeSourcePath.of(filesystem, "path/in/depfile");
    nonDepfileInput = FakeSourcePath.of(filesystem, "path/not/in/depfile");
    RulePipelineStateFactory<SimplePipelineState> pipelineStateFactory =
        (context, filesystem, firstTarget) -> new SimplePipelineState();
    rootRule = new SimpleNoopRule(BUILD_TARGET.withFlavors(InternalFlavor.of("root")), filesystem);
    dependency =
        new InitializableFromDiskRule(
            BUILD_TARGET.withFlavors(InternalFlavor.of("child")),
            filesystem,
            rootRule,
            ImmutableSortedSet.of(depfileInput, nonDepfileInput),
            ImmutableSortedSet.of(depfileInput),
            pipelineStateFactory,
            PipelineType.MIDDLE);
    buildRule =
        new InitializableFromDiskRule(
            BUILD_TARGET,
            filesystem,
            dependency,
            ImmutableSortedSet.of(depfileInput, nonDepfileInput),
            ImmutableSortedSet.of(depfileInput),
            pipelineStateFactory,
            pipelineType);
    dependent =
        new InitializableFromDiskRule(
            BUILD_TARGET.withFlavors(InternalFlavor.of("parent")),
            filesystem,
            buildRule,
            ImmutableSortedSet.of(depfileInput, nonDepfileInput),
            ImmutableSortedSet.of(depfileInput),
            pipelineStateFactory,
            PipelineType.MIDDLE);
    graphBuilder.addToIndex(dependency);
    graphBuilder.addToIndex(buildRule);
    graphBuilder.addToIndex(dependent);
    reset();
  }

  public void reset() throws IOException {
    writeDepfileInput(DEPFILE_INPUT_CONTENT);
    writeNonDepfileInput(NON_DEPFILE_INPUT_CONTENT);
    rootRule.value = 0;
    artifactCache = new InMemoryArtifactCache();
    lastSuccessType = null;
    doClean();
  }

  @Test
  public void runTestForAllSuccessTypes() throws Exception {
    // This is done to ensure that every success type is covered by a test.
    // TODO(cjhopman): add test for failed case.
    for (BuildRuleSuccessType successType : EnumSet.allOf(BuildRuleSuccessType.class)) {
      reset();
      try {
        switch (successType) {
          case BUILT_LOCALLY:
            testBuiltLocally();
            break;
          case FETCHED_FROM_CACHE:
            testFetchedFromCache();
            break;
          case MATCHING_RULE_KEY:
            testMatchingRuleKey();
            break;
          case FETCHED_FROM_CACHE_INPUT_BASED:
            testFetchedFromCacheInputBased();
            break;
          case FETCHED_FROM_CACHE_MANIFEST_BASED:
            testFetchedFromCacheManifestBased();
            break;
          case MATCHING_INPUT_BASED_RULE_KEY:
            testMatchingInputBasedKey();
            break;
          case MATCHING_DEP_FILE_RULE_KEY:
            testMatchingDepFileKey();
            break;
            // Every success type should be covered by a test. Don't add a default clause here.
        }
        assertEquals(lastSuccessType, successType);
      } catch (AssumptionViolatedException exception) {
        // Ignore these. They indicate we have a test for it but have explicitly disabled it in some
        // cases. Also, I hate PMD.
        System.err.println("Ignoring assumption failure for " + successType);
      }
    }
  }

  @Test
  public void testMatchingRuleKey() throws Exception {
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertEquals(
        "Matching rule key should not invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testMatchingInputBasedKey() throws Exception {
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    rootRule.value = 1;
    assertEquals(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertEquals(
        "Matching input based rule key should not invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testMatchingDepFileKey() throws Exception {
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeNonDepfileInput("something else");
    assertEquals(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertEquals(
        "Matching rule key should not invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testFetchedFromCache() throws Exception {
    // write to cache
    String newContent = "new content";
    writeDepfileInput(newContent);
    // populate cache
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    doClean();

    writeDepfileInput(DEPFILE_INPUT_CONTENT);
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput(newContent);
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Fetching from cache should invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testFetchedFromCacheInputBased() throws Exception {
    // write to cache
    String newContent = "new content";
    writeDepfileInput(newContent);
    // populate cache
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    doClean();

    writeDepfileInput(DEPFILE_INPUT_CONTENT);
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    rootRule.value = 1;
    writeDepfileInput(newContent);
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, doBuild().getSuccess());

    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Fetching from cache should invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testFetchedFromCacheManifestBased() throws Exception {
    Assume.assumeFalse(
        "Manifest append depends on a manifest being downloaded. In a pipeline, a manifest"
            + " is only downloaded for the first rule that is built locally.",
        pipelineType == PipelineType.MIDDLE);
    // write to cache
    String newContent = "new content";
    writeDepfileInput(newContent);
    // populate cache
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    doClean();

    writeDepfileInput(DEPFILE_INPUT_CONTENT);
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput(newContent);
    writeNonDepfileInput(newContent);
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED, doBuild().getSuccess());

    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Fetching from cache should invalidate InitializableFromDisk state.",
        firstState,
        secondState);
  }

  @Test
  public void testBuiltLocally() throws Exception {
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput("new content");
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Building locally should invalidate InitializableFromDisk state.", firstState, secondState);
  }

  @Test(timeout = 10000)
  public void testBuildLocallyWithImmediateRemoteSynchronization() throws Exception {
    RemoteBuildRuleSynchronizer synchronizer = createRemoteBuildRuleSynchronizer();
    synchronizer.switchToAlwaysWaitingMode();

    // Signal completion of the build rule before the caching build engine requests it.
    // waitForBuildRuleToFinishRemotely call inside caching build engine should result in an
    // ImmediateFuture with the completion handler executed on the caching build engine's thread.
    synchronizer.signalCompletionOfBuildRule(dependency.getFullyQualifiedName());
    synchronizer.signalCompletionOfBuildRule(buildRule.getFullyQualifiedName());
    synchronizer.signalCompletionOfBuildRule(dependent.getFullyQualifiedName());

    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild(synchronizer).getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput("new content");
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Building locally should invalidate InitializableFromDisk state.", firstState, secondState);

    // Check that the build engine waited for the remote build of rule to finish.
    assertTrue(
        RemoteBuildRuleSynchronizerTestUtil.buildCompletionWaitingFutureCreatedForTarget(
            synchronizer, BUILD_TARGET.getFullyQualifiedName()));

    synchronizer.close();
  }

  @Test(timeout = 10000)
  public void testBuildLocallyWithDelayedRemoteSynchronization() throws Exception {
    RemoteBuildRuleSynchronizer synchronizer = createRemoteBuildRuleSynchronizer();
    synchronizer.switchToAlwaysWaitingMode();

    // Signal the completion of the build rule asynchronously.
    // waitForBuildRuleToFinishRemotely call inside caching build engine should result in an
    // Future that later has its completion handler invoked by the Thread below.
    Thread signalBuildRuleCompletedThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(10);
              } catch (InterruptedException e) {
                fail("Test was interrupted");
              }
              synchronizer.signalCompletionOfBuildRule(dependency.getFullyQualifiedName());
              synchronizer.signalCompletionOfBuildRule(buildRule.getFullyQualifiedName());
              synchronizer.signalCompletionOfBuildRule(dependent.getFullyQualifiedName());
            });
    signalBuildRuleCompletedThread.start();

    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild(synchronizer).getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput("new content");
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Building locally should invalidate InitializableFromDisk state.", firstState, secondState);

    signalBuildRuleCompletedThread.join(1000);

    // Check that the build engine waited for the remote build of rule to finish.
    assertTrue(
        RemoteBuildRuleSynchronizerTestUtil.buildCompletionWaitingFutureCreatedForTarget(
            synchronizer, BUILD_TARGET.getFullyQualifiedName()));

    synchronizer.close();
  }

  @Test(timeout = 10000)
  public void testBuildLocallyWhenRemoteBuildNotStartedAndAlwaysWaitSetToFalse() throws Exception {
    RemoteBuildRuleSynchronizer synchronizer = createRemoteBuildRuleSynchronizer();

    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild(synchronizer).getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput("new content");
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Building locally should invalidate InitializableFromDisk state.", firstState, secondState);

    // Check that the build engine did not wait for the remote build of rule to finish
    assertFalse(
        RemoteBuildRuleSynchronizerTestUtil.buildCompletionWaitingFutureCreatedForTarget(
            synchronizer, BUILD_TARGET.getFullyQualifiedName()));

    synchronizer.close();
  }

  @Test(timeout = 10000)
  public void testBuildLocallyWhenRemoteBuildStartedAndAlwaysWaitSetToFalse() throws Exception {
    RemoteBuildRuleSynchronizer synchronizer = createRemoteBuildRuleSynchronizer();

    // Signal that the build has started, which should ensure build waits.
    synchronizer.signalStartedRemoteBuildingOfBuildRule(BUILD_TARGET.getFullyQualifiedName());

    // Signal the completion of the build rule asynchronously.
    // waitForBuildRuleToFinishRemotely call inside caching build engine should result in an
    // Future that later has its completion handler invoked by the Thread below.
    Thread signalBuildRuleCompletedThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(10);
              } catch (InterruptedException e) {
                fail("Test was interrupted");
              }
              synchronizer.signalCompletionOfBuildRule(BUILD_TARGET.getFullyQualifiedName());
            });
    signalBuildRuleCompletedThread.start();

    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild(synchronizer).getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object firstState = buildRule.getBuildOutputInitializer().getBuildOutput();

    writeDepfileInput("new content");
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, doBuild().getSuccess());
    assertTrue(buildRule.isInitializedFromDisk());
    Object secondState = buildRule.getBuildOutputInitializer().getBuildOutput();

    assertNotEquals(
        "Building locally should invalidate InitializableFromDisk state.", firstState, secondState);

    signalBuildRuleCompletedThread.join(1000);

    // Check that the build engine waited for the remote build of rule to finish.
    assertTrue(
        RemoteBuildRuleSynchronizerTestUtil.buildCompletionWaitingFutureCreatedForTarget(
            synchronizer, BUILD_TARGET.getFullyQualifiedName()));

    synchronizer.close();
  }

  private BuildEngineBuildContext createBuildContext(BuildId buildId) {
    return BuildEngineBuildContext.builder()
        .setBuildContext(FakeBuildContext.withSourcePathResolver(pathResolver))
        .setClock(new DefaultClock())
        .setBuildId(buildId)
        .setArtifactCache(artifactCache)
        .build();
  }

  private RemoteBuildRuleSynchronizer createRemoteBuildRuleSynchronizer() {
    // Allow only up to 2 very quick backoffs.
    return new RemoteBuildRuleSynchronizer(
        new DefaultClock(), Executors.newSingleThreadScheduledExecutor(), 6, 10);
  }

  private void writeDepfileInput(String content) throws IOException {
    filesystem.mkdirs(depfileInput.getRelativePath().getParent());
    filesystem.writeContentsToPath(content, depfileInput.getRelativePath());
  }

  private void writeNonDepfileInput(String content) throws IOException {
    filesystem.mkdirs(nonDepfileInput.getRelativePath().getParent());
    filesystem.writeContentsToPath(content, nonDepfileInput.getRelativePath());
  }

  private BuildResult doBuild() throws Exception {
    return doBuild(defaultRemoteBuildRuleCompletionWaiter);
  }

  private BuildResult doBuild(RemoteBuildRuleCompletionWaiter synchronizer) throws Exception {
    fileHashCache.invalidateAll();
    try (CachingBuildEngine cachingBuildEngine =
        cachingBuildEngineFactory(synchronizer)
            .setDepFiles(DepFiles.CACHE)
            .setRuleKeyFactories(
                RuleKeyFactories.of(
                    new DefaultRuleKeyFactory(
                        CachingBuildEngineTest.FIELD_LOADER,
                        fileHashCache,
                        pathResolver,
                        ruleFinder),
                    new TestInputBasedRuleKeyFactory(
                        CachingBuildEngineTest.FIELD_LOADER,
                        fileHashCache,
                        pathResolver,
                        ruleFinder,
                        CachingBuildEngineTest.NO_INPUT_FILE_SIZE_LIMIT),
                    new DefaultDependencyFileRuleKeyFactory(
                        CachingBuildEngineTest.FIELD_LOADER,
                        fileHashCache,
                        pathResolver,
                        ruleFinder)))
            .build()) {
      // Build the dependent.
      BuildId buildId = new BuildId();
      buildAndGetResult(cachingBuildEngine, dependent, buildId);
      BuildResult result = buildAndGetResult(cachingBuildEngine, buildRule, buildId);

      if (DEBUG) {
        System.err.println(
            "dependency success_type="
                + buildAndGetResult(cachingBuildEngine, dependency, buildId).getSuccess());
        System.err.println("buildRule success_type=" + result.getSuccess());
        System.err.println(
            "dependent success_type="
                + buildAndGetResult(cachingBuildEngine, dependent, buildId).getSuccess());
      }

      // The rule's already built, this just get's it's result.
      if (DEBUG && !result.isSuccess()) {
        result.getFailure().printStackTrace();
      }
      lastSuccessType = result.getSuccess();
      return result;
    }
  }

  private BuildResult buildAndGetResult(
      CachingBuildEngine cachingBuildEngine, InitializableFromDiskRule dependent, BuildId buildId)
      throws InterruptedException, java.util.concurrent.ExecutionException {
    return cachingBuildEngine
        .build(createBuildContext(buildId), TestExecutionContext.newInstance(), dependent)
        .getResult()
        .get();
  }

  private void doClean() throws IOException {
    buildInfoStoreManager.close();
    buildInfoStoreManager = new BuildInfoStoreManager();
    filesystem.deleteRecursivelyIfExists(filesystem.getBuckPaths().getBuckOut());
    Files.createDirectories(filesystem.resolve(filesystem.getBuckPaths().getScratchDir()));
    buildInfoStore = buildInfoStoreManager.get(filesystem, metadataStorage);
    System.out.println(
        buildInfoStore.readMetadata(dependency.getBuildTarget(), BuildInfo.MetadataKey.RULE_KEY));
  }

  private static class SimplePipelineState implements RulePipelineState {
    @Override
    public void close() {}
  }
}
