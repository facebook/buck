/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEventAtTime;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.json.HasJsonField;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.TriState;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.network.RemoteLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RemoteLogUploaderEventListenerTest {

  private static class TestRemoteLogger implements RemoteLogger {
    List<String> loggedEntries = new ArrayList<>();

    @Override
    public Optional<ListenableFuture<Void>> log(String logLine) {
      loggedEntries.add(logLine);
      return Optional.absent();
    }

    @Override
    public ListenableFuture<Void> close() {
      return Futures.immediateFuture(null);
    }
  }

  private ObjectMapper objectMapper = new ObjectMapper();

  private static final BuildEnvironmentDescription BUILD_ENVIRONMENT_DESCRIPTION =
      BuildEnvironmentDescription.builder()
          .setUser("user")
          .setHostname("hostname")
          .setOs("os")
          .setAvailableCores(1)
          .setSystemMemoryMb(1024)
          .setBuckDirty(TriState.UNSPECIFIED)
          .setBuckCommit("unknown")
          .setJavaVersion("1.7")
          .build();

  @Test
  @SuppressWarnings("unchecked")
  public void testAuxEventSentInAdditionToOriginal() throws Exception {
    FluentIterable<JsonNode> nodes = getJsonNodes(
        CommandEvent.started(
            "cook",
            ImmutableList.of("--dinner"),
            true));

    assertThat(
        nodes,
        Matchers.containsInAnyOrder(
            hasJsonField("type", "CommandStarted"),
            hasJsonField("type", "CommandStartedAux")
        ));
  }

  @Test
  public void testGivesUpAfterConsecutiveFailures() throws Exception {
    final AtomicInteger logCount = new AtomicInteger(0);
    RemoteLogger consistentlyFailingLogger = new RemoteLogger() {

      @Override
      public Optional<ListenableFuture<Void>> log(String logLine) {
        logCount.incrementAndGet();
        return Optional.of(Futures.<Void>immediateFailedFuture(new Throwable()));
      }

      @Override
      public ListenableFuture<Void> close() {
        return Futures.immediateFuture(null);
      }
    };
    RemoteLogUploaderEventListener eventListener =
        new RemoteLogUploaderEventListener(
            objectMapper,
            consistentlyFailingLogger,
            BUILD_ENVIRONMENT_DESCRIPTION);
    EventBus eventBus = new EventBus();
    eventBus.register(eventListener);

    final int maxRepetitions = 2 * RemoteLogUploaderEventListener.MAX_FAILURE_COUNT;
    for (int i = 0; i < maxRepetitions; ++i) {
      eventBus.post(configureTestEvent(BuildEvent.started(ImmutableSet.of(""))));
    }

    assertThat(logCount.get(), Matchers.lessThan(maxRepetitions));
  }

  @Test
  public void testDoesNotGiveUpAfterSparseFailures() throws Exception {
    final AtomicInteger logCount = new AtomicInteger(0);
    RemoteLogger failingEveryOtherTimeLogger = new RemoteLogger() {

      @Override
      public Optional<ListenableFuture<Void>> log(String logLine) {
        System.out.println(logLine);
        int i = logCount.incrementAndGet();
        if (i % 2 == 0) {
          return Optional.of(Futures.<Void>immediateFailedFuture(new Throwable()));
        } else {
          return Optional.of(Futures.<Void>immediateFuture(null));
        }
      }

      @Override
      public ListenableFuture<Void> close() {
        return Futures.immediateFuture(null);
      }
    };
    RemoteLogUploaderEventListener eventListener =
        new RemoteLogUploaderEventListener(
            objectMapper,
            failingEveryOtherTimeLogger,
            BUILD_ENVIRONMENT_DESCRIPTION);
    EventBus eventBus = new EventBus();
    eventBus.register(eventListener);

    final int maxRepetitions = 2 * RemoteLogUploaderEventListener.MAX_FAILURE_COUNT;
    for (int i = 0; i < maxRepetitions; ++i) {
      eventBus.post(configureTestEvent(BuildEvent.started(ImmutableSet.of(""))));
    }

    assertThat(logCount.get(), Matchers.equalTo(maxRepetitions));
  }

  private Matcher<JsonNode> hasJsonField(
      String fieldName,
      Matcher<? super JsonNode> matcher) {
    return new HasJsonField(objectMapper, fieldName, matcher);
  }

  private <T> Matcher<JsonNode> hasJsonField(
      String fieldName,
      T value) {
    return new HasJsonField(
        objectMapper,
        fieldName,
        Matchers.equalTo(objectMapper.valueToTree(value)));
  }

  private Matcher<JsonNode> hasJsonField(String fieldName) {
    return hasJsonField(fieldName, Matchers.anything());
  }

  private FluentIterable<JsonNode> getJsonNodes(AbstractBuckEvent event) {
    TestRemoteLogger logger = new TestRemoteLogger();
    RemoteLogUploaderEventListener eventListener =
        new RemoteLogUploaderEventListener(
            objectMapper,
            logger,
            BUILD_ENVIRONMENT_DESCRIPTION);
    EventBus eventBus = new EventBus();
    eventBus.register(eventListener);

    AbstractBuckEvent configuredEvent = configureTestEvent(event);
    eventBus.post(configuredEvent);

    return FluentIterable.from(logger.loggedEntries)
        .transform(
            new Function<String, JsonNode>() {
              @Override
              public JsonNode apply(String input) {
                try {
                  return objectMapper.readTree(input);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            });
  }

  private void singleSchemaTest(AbstractBuckEvent event, Matcher<JsonNode> eventMatcher) {
    FluentIterable<JsonNode> loggedNodes = getJsonNodes(event);
    Matcher<JsonNode> nodeMatcher = Matchers.allOf(
        hasJsonField("buildId", BuckEventBusFactory.BUILD_ID_FOR_TEST.toString()),
        hasJsonField("type"),
        eventMatcher
    );
    assertThat(loggedNodes, Matchers.hasItem(nodeMatcher));
  }

  private final Matcher<JsonNode> buildRuleShapeMatcher = Matchers.allOf(
      hasJsonField("type"),
      hasJsonField("name")
  );

  private final Matcher<JsonNode> eventShapeMatcher = Matchers.allOf(
      hasJsonField("timestamp"),
      hasJsonField("threadId"),
      hasJsonField("eventKey")
  );

  /**
   * The purpose of this is to document the set of properties that are stable-ish, if you need
   * to remove or rename any of these you'll want to give the community and the team a heads up
   * and increment the protocol version in
   * {@link com.facebook.buck.util.environment.AbstractBuildEnvironmentDescription}.
   */
  @Test
  public void testSchema() throws Exception {
    singleSchemaTest(
        CommandEvent.started("cook", ImmutableList.of("--dinner"), true),
        Matchers.allOf(
            hasJsonField("type", "CommandStarted"),
            eventShapeMatcher,
            hasJsonField("args", Arrays.asList("--dinner")),
            hasJsonField("commandName", "cook"),
            hasJsonField("daemon", true)
        ));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule buildRule = createFakeBuildRule("//build:rule1", resolver, new RuleKey("aaaa"));
    FakeBuildRule buildRule2 = createFakeBuildRule(
        "//build:rule2",
        resolver,
        ImmutableSortedSet.<BuildRule>of(buildRule),
        new RuleKey("aaaa"));

    singleSchemaTest(
        BuildRuleEvent.started(buildRule),
        Matchers.allOf(
            hasJsonField("type", "BuildRuleStarted"),
            eventShapeMatcher,
            hasJsonField(
                "buildRule",
                Matchers.allOf(
                    hasJsonField("type", "fake_build_rule"),
                    hasJsonField("name", "//build:rule1")
                ))));

    singleSchemaTest(
        BuildRuleEvent.started(buildRule2),
        Matchers.allOf(
            hasJsonField("type", "BuildRuleStartedAux"),
            hasJsonField("buildRule", buildRuleShapeMatcher),
            hasJsonField("buildRule", hasJsonField("name", "//build:rule2")),
            hasJsonField("deps", Matchers.contains(buildRuleShapeMatcher)),
            hasJsonField("deps", Matchers.contains(hasJsonField("name", "//build:rule1")))
        ));
  }

  private static <T extends AbstractBuckEvent> T configureTestEvent(T event) {
    return configureTestEventAtTime(event, 0L, TimeUnit.SECONDS, 1L);
  }

  private FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      RuleKey ruleKey) {
    return createFakeBuildRule(target, resolver, ImmutableSortedSet.<BuildRule>of(), ruleKey);
  }

  private FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps,
      RuleKey ruleKey) {
    FakeBuildRule fakeBuildRule = new FakeBuildRule(
        BuildTargetFactory.newInstance(target),
        resolver,
        deps);
    fakeBuildRule.setRuleKey(ruleKey);
    return fakeBuildRule;
  }
}
