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

import static com.facebook.buck.rules.BuildRuleStatus.FAIL;
import static com.facebook.buck.rules.BuildRuleStatus.SUCCESS;
import static com.facebook.buck.rules.CacheResult.HIT;
import static com.facebook.buck.rules.CacheResult.MISS;
import static com.facebook.buck.util.BuckConstant.BIN_DIR;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.CommandRunner;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class AbstractCachingBuildRuleTest {

  private static final BuildTarget buildTarget = BuildTargetFactory.newInstance(
      "//src/com/facebook/orca", "orca");

  private static final ImmutableList<BuildTarget> inputTargets = ImmutableList.of(
      BuildTargetFactory.newInstance("//src/com/facebook/orca/Thing1.java", "Thing1.java"),
      BuildTargetFactory.newInstance("//src/com/facebook/orca/Thing2.java", "Thing2.java"),
      BuildTargetFactory.newInstance("//src/com/facebook/orca/Thing3.java", "Thing3.java"));

  @Test
  public void testNotCachedIfDepsNotCached() throws IOException {
    BuildContext context = BuildContext.builder()
        .setProjectRoot(createMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(createMock(CommandRunner.class))
        .setProjectFilesystem(createMock(ProjectFilesystem.class))
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .build();

    // Create three deps: the second one is not cached.
    BuildRule dep1 = createMock(BuildRule.class);
    expect(dep1.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep1.isCached(context)).andReturn(true);
    BuildRule dep2 = createMock(BuildRule.class);
    expect(dep2.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep2.isCached(context)).andReturn(false);
    expect(dep2.getFullyQualifiedName()).andReturn("//src/com/facebook/base:base");
    BuildRule dep3 = createMock(BuildRule.class);
    expect(dep3.isVisibleTo(buildTarget)).andReturn(true);

    // Verify the call to the logger so we know the rule was not cached for the right reason.
    Logger logger = createMock(Logger.class);
    logger.info("//src/com/facebook/orca:orca not cached because" +
        " //src/com/facebook/base:base is not cached");

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Replay the mocks so checkIsCached() can be run and verify they are used as expected.
    replay(dep1, dep2, dep3, logger, visibilityPatterns);
    AbstractCachingBuildRule cachingRule = createRule(ImmutableSet.of(dep1, dep2, dep3),
        visibilityPatterns);
    boolean isCached = cachingRule.checkIsCached(context, logger);
    assertFalse("The rule should not be cached", isCached);
    verify(dep1, dep2, dep3, logger, visibilityPatterns);
  }

  @Test
  public void testNotCachedIfSuccessFileDoesNotExist() throws IOException {
    // Create a LastModifiedService that checks for the existence of a file.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.exists(BIN_DIR + "/src/com/facebook/orca/.success/orca"))
        .andReturn(false);
    BuildContext context = BuildContext.builder()
        .setProjectRoot(createMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(createMock(CommandRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .build();

    // Create three deps, all of which are cached.
    BuildRule dep1 = createMock(BuildRule.class);
    expect(dep1.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep1.isCached(context)).andReturn(true);
    BuildRule dep2 = createMock(BuildRule.class);
    expect(dep2.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep2.isCached(context)).andReturn(true);
    BuildRule dep3 = createMock(BuildRule.class);
    expect(dep3.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep3.isCached(context)).andReturn(true);

    // Verify the call to the logger so we know the rule was not cached for the right reason.
    Logger logger = createMock(Logger.class);
    logger.info("//src/com/facebook/orca:orca not cached because the output file " +
        BIN_DIR + "/src/com/facebook/orca/.success/orca is not built");

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Replay the mocks so checkIsCached() can be run and verify they are used as expected.
    replay(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
    AbstractCachingBuildRule cachingRule = createRule(ImmutableSet.of(dep1, dep2, dep3),
        visibilityPatterns);
    boolean isCached = cachingRule.checkIsCached(context, logger);
    assertFalse("The rule should not be cached", isCached);
    verify(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
  }

  @Test
  public void testNotCachedIfInputsHaveChanged() throws IOException {
    // Create a LastModifiedService that checks for the existence of a file and then its contents.
    ProjectFilesystem projectFilesystem = createService(false);
    BuildContext context = BuildContext.builder()
        .setProjectRoot(createMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(createMock(CommandRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .build();

    // Create three deps, all of which are cached.
    BuildRule dep1 = createMock(BuildRule.class);
    expect(dep1.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep1.isCached(context)).andReturn(true);
    BuildRule dep2 = createMock(BuildRule.class);
    expect(dep2.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep2.isCached(context)).andReturn(true);
    BuildRule dep3 = createMock(BuildRule.class);
    expect(dep3.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep3.isCached(context)).andReturn(true);

    // Verify the call to the logger so we know the rule was not cached for the right reason.
    Logger logger = createMock(Logger.class);
    logger.info(String.format(
        "%s not cached because the inputs and/or their contents have changed", buildTarget));

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Replay the mocks so checkIsCached() can be run and verify they are used as expected.
    replay(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
    AbstractCachingBuildRule cachingRule = createRule(ImmutableSet.of(dep1, dep2, dep3),
        visibilityPatterns);
    boolean isCached = cachingRule.checkIsCached(context, logger);
    assertFalse("The rule should not be cached", isCached);
    verify(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
  }

  @Test
  public void testNotCachedIfInputFilesHaveBeenModifiedSinceTheLastBuild() throws IOException {
    // If the build file is modified after the success file, then the rule should not be cached.
    ProjectFilesystem projectFilesystem = createService(false);
    BuildContext context = BuildContext.builder()
        .setProjectRoot(createMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(createMock(CommandRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .build();

    // Create three deps, all of which are cached.
    BuildRule dep1 = createMock(BuildRule.class);
    expect(dep1.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep1.isCached(context)).andReturn(true);
    BuildRule dep2 = createMock(BuildRule.class);
    expect(dep2.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep2.isCached(context)).andReturn(true);
    BuildRule dep3 = createMock(BuildRule.class);
    expect(dep3.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep3.isCached(context)).andReturn(true);

    // Verify the call to the logger so we know the rule was not cached for the right reason.
    Logger logger = createMock(Logger.class);
    logger.info(String.format(
        "%s not cached because the inputs and/or their contents have changed", buildTarget));

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Replay the mocks so checkIsCached() can be run and verify they are used as expected.
    replay(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
    AbstractCachingBuildRule cachingRule = createRule(ImmutableSet.of(dep1, dep2, dep3),
        visibilityPatterns);
    boolean isCached = cachingRule.checkIsCached(context, logger);
    assertFalse("The rule should not be cached", isCached);
    verify(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
  }

  @Test
  public void testCachedIfAllCachingCriteriaAreSatisfied() throws IOException {
    // If the build file is modified at the same time as the success file, then it can still be
    // cached.
    ProjectFilesystem projectFilesystem = createService(true);
    BuildContext context = BuildContext.builder()
        .setProjectRoot(createMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(createMock(CommandRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .build();

    // Create three deps, all of which are cached.
    BuildRule dep1 = createMock(BuildRule.class);
    expect(dep1.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep1.isCached(context)).andReturn(true);
    BuildRule dep2 = createMock(BuildRule.class);
    expect(dep2.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep2.isCached(context)).andReturn(true);
    BuildRule dep3 = createMock(BuildRule.class);
    expect(dep3.isVisibleTo(buildTarget)).andReturn(true);
    expect(dep3.isCached(context)).andReturn(true);

    // Verify that there are no calls made to the Logger.
    Logger logger = createMock(Logger.class);

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Replay the mocks so checkIsCached() can be run and verify they are used as expected.
    replay(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
    AbstractCachingBuildRule cachingRule = createRule(ImmutableSet.of(dep1, dep2, dep3),
        visibilityPatterns);
    boolean isCached = cachingRule.checkIsCached(context, logger);
    assertTrue("The rule should be cached", isCached);
    verify(projectFilesystem, dep1, dep2, dep3, logger, visibilityPatterns);
  }

  @SuppressWarnings("unchecked")
  private static ProjectFilesystem createService(final boolean isCached) throws IOException {
    // Create a LastModifiedService that:
    // (1) checks for the existence of the success file,
    // (2) verifies that its contents match the list of inputs and input contents
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.exists(BIN_DIR + "/src/com/facebook/orca/.success/orca"))
        .andReturn(true);
    expect(projectFilesystem.isMatchingFileContents(isA(Iterable.class),
        eq(BIN_DIR + "/src/com/facebook/orca/.success/orca")))
        .andAnswer(new IAnswer<Boolean>() {
          @Override
          public Boolean answer() throws Throwable {
            ImmutableList<String> arg0 = ImmutableList.<String>builder()
                .addAll((Iterable<String>) getCurrentArguments()[0])
                .build();

            assertTrue(inputTargets.size() == arg0.size());
            for (int i = 0; i < inputTargets.size(); i++) {
              // Extract fully qualified name from success file line, which has format:
              //   OutputKey RuleKey FullyQualifiedName
              String[] tuple = arg0.get(i).split(" ", 3);
              assertTrue(tuple[2].equals(inputTargets.get(i).getFullyQualifiedName()));
            }
            return isCached;
          }
        });
    return projectFilesystem;
  }

  private static AbstractCachingBuildRule createRule(ImmutableSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    BuildRuleParams buildRuleParams = new BuildRuleParams(
        buildTarget, sortedDeps, visibilityPatterns);
    return new AbstractCachingBuildRule(buildRuleParams) {
      @Override
      public BuildRuleType getType() {
        throw new IllegalStateException("This method should not be called");
      }

      @Override
      protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
        List<String> inputs = Lists.newArrayList();
        for (BuildTarget inputTarget : inputTargets) {
          inputs.add(inputTarget.getBasePath());
        }
        return inputs;
      }

      @Override
      protected List<Command> buildInternal(BuildContext context) throws IOException {
        throw new IllegalStateException("This method should not be called");
      }
    };
  }


  /**
   * Because {@link AbstractCachingBuildRule#build(BuildContext)} returns a
   * {@link ListenableFuture}, any exceptions that are thrown during the invocation of that method
   * should ideally be reflected as a failed future rather than being thrown and bubbling up to the
   * top-level.
   */
  @Test
  public void testExceptionDuringBuildYieldsFailedFuture() throws InterruptedException {
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        BuildTargetFactory.newInstance("//java/src/com/example/base:base"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    final IOException exceptionThrownDuringBuildInternal = new IOException("some exception");
    AbstractCachingBuildRule buildRule = new AbstractCachingBuildRule(buildRuleParams) {

      @Override
      protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
        return ImmutableList.of();
      }

      @Override
      protected List<Command> buildInternal(BuildContext context)
          throws IOException {
        throw exceptionThrownDuringBuildInternal;
      }

      @Override
      public BuildRuleType getType() {
        return BuildRuleType.JAVA_LIBRARY;
      }

      @Override
      boolean checkIsCached(BuildContext context, Logger logger) throws IOException {
        return false;
      }
    };

    BuildContext buildContext = createMock(BuildContext.class);
    ExecutorService executor = MoreExecutors.sameThreadExecutor();
    expect(buildContext.getExecutor()).andReturn(executor).times(3);
    expect(buildContext.getEventBus()).andStubReturn(new EventBus());

    CommandRunner commandRunner = createMock(CommandRunner.class);
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(executor);
    expect(commandRunner.getListeningExecutorService()).andReturn(executorService);

    expect(buildContext.getCommandRunner()).andReturn(commandRunner);
    replay(buildContext, commandRunner);

    ListenableFuture<BuildRuleSuccess> buildRuleSuccess = buildRule.build(buildContext);
    try {
      buildRuleSuccess.get();
      fail("Should have thrown ExecutionException");
    } catch (ExecutionException e) {
      assertSame("The build rule should have the IOException packaged in an ExecutionException.",
          exceptionThrownDuringBuildInternal,
          e.getCause());
    }

    assertFalse("The rule should not be considered to have built successfully.",
        buildRule.isRuleBuilt());

    verify(buildContext, commandRunner);
  }

  @Test
  public void whenBuildFinishesThenBuildSuccessEventFired()
      throws ExecutionException, InterruptedException {
    BuildTarget target = BuildTargetFactory.newInstance("//com/example:rule");
    BuildRuleParams params = new BuildRuleParams(target, ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));

    CommandRunner commandRunner = createNiceMock(CommandRunner.class);
    expect(commandRunner.getListeningExecutorService()).andStubReturn(
        MoreExecutors.sameThreadExecutor());
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);

    replay(commandRunner, packageFinder);

    EventBus bus = new EventBus();
    Listener listener = new Listener();
    bus.register(listener);
    DummyRule rule = new DummyRule(params, false, false);
    File root = new File(".");
    BuildContext context = BuildContext.builder()
        .setEventBus(bus)
        .setProjectRoot(root)
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setCommandRunner(commandRunner)
        .setJavaPackageFinder(packageFinder)
        .build();


    ListenableFuture<BuildRuleSuccess> build = rule.build(context);
    build.get();

    assertSeenEventsContain(ImmutableList.<BuildEvent>of(
        BuildEvents.started(rule), BuildEvents.finished(rule, SUCCESS, MISS)),
        listener.getSeen());
  }

  @Test
  public void whenCacheRaisesExceptionThenBuildFailEventFired()
      throws ExecutionException, InterruptedException {
    BuildTarget target = BuildTargetFactory.newInstance("//com/example:rule");
    BuildRuleParams params = new BuildRuleParams(target, ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));

    CommandRunner commandRunner = createNiceMock(CommandRunner.class);
    expect(commandRunner.getListeningExecutorService()).andStubReturn(
        MoreExecutors.sameThreadExecutor());
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);

    replay(commandRunner, packageFinder);

    EventBus bus = new EventBus();
    Listener listener = new Listener();
    bus.register(listener);
    DummyRule rule = new DummyRule(params, false, /* cache detonates */ true);
    File root = new File(".");
    BuildContext context = BuildContext.builder()
        .setEventBus(bus)
        .setProjectRoot(root)
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setCommandRunner(commandRunner)
        .setJavaPackageFinder(packageFinder)
        .build();


    ListenableFuture<BuildRuleSuccess> build = rule.build(context);
    try {
      build.get();
      fail("Cache should have thrown an IOException");
    } catch (ExecutionException ignored) {
    }

    assertSeenEventsContain(ImmutableList.<BuildEvent>of(
        BuildEvents.started(rule), BuildEvents.finished(rule, FAIL, MISS)),
        listener.getSeen());
  }

  @Test
  public void whenBuildResultCachedThenBuildCachedEventFired()
      throws ExecutionException, InterruptedException {
    BuildTarget target = BuildTargetFactory.newInstance("//com/example:rule");
    BuildRuleParams params = new BuildRuleParams(target, ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));

    CommandRunner commandRunner = createNiceMock(CommandRunner.class);
    expect(commandRunner.getListeningExecutorService()).andStubReturn(
        MoreExecutors.sameThreadExecutor());
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);

    replay(commandRunner, packageFinder);

    EventBus bus = new EventBus();
    Listener listener = new Listener();
    bus.register(listener);
    DummyRule rule = new DummyRule(params, /* cached */ true, /* cache detonates */ false);
    File root = new File(".");
    BuildContext context = BuildContext.builder()
        .setEventBus(bus)
        .setProjectRoot(root)
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setCommandRunner(commandRunner)
        .setJavaPackageFinder(packageFinder)
        .build();


    ListenableFuture<BuildRuleSuccess> build = rule.build(context);
    build.get();

    assertSeenEventsContain(ImmutableList.<BuildEvent>of(
        BuildEvents.started(rule), BuildEvents.finished(rule, SUCCESS, HIT)),
        listener.getSeen());
  }

  private void assertSeenEventsContain(List<BuildEvent> expected, List<BuildEvent> seen) {
    for (BuildEvent buildEvent : expected) {
      assertTrue(String.format("Did not see %s in %s", buildEvent, seen),
          seen.contains(buildEvent));
    }
  }

  private static class DummyRule extends AbstractCachingBuildRule {

    private final boolean cached;
    private final boolean cacheDetonates;

    protected DummyRule(BuildRuleParams buildRuleParams, boolean isCached, boolean cacheDetonates) {
      super(buildRuleParams);
      cached = isCached;
      this.cacheDetonates = cacheDetonates;
    }

    @Override
    boolean checkIsCached(BuildContext context, Logger logger) throws IOException {
      if (cacheDetonates) {
        throw new IOException("Cache is somehow b0rked");
      }

      return cached;
    }

    @Override
    protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
      return ImmutableSet.of();
    }

    @Override
    protected List<Command> buildInternal(BuildContext context) throws IOException {
      return ImmutableList.of();
    }

    @Override
    public BuildRuleType getType() {
      return BuildRuleType.GENRULE;
    }
  }

  private static class Listener {
    private List<BuildEvent> seen = Lists.newArrayList();

    @Subscribe
    @SuppressWarnings("unused")
    public void eventFired(BuildEvent event) {
      seen.add(event);
    }

    public List<BuildEvent> getSeen() {
      return seen;
    }
  }
}
