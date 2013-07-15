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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.FakeStep;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ChromeTraceBuildListenerTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testBuildJson() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(projectFilesystem);

    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");

    BuildRule rule = new FakeBuildRule(
        new BuildRuleType("fakeRule"),
        target,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    FakeStep step = new FakeStep("fakeStep", "I'm a Fake Step!", 0);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableList<BuildTarget> buildTargets = ImmutableList.of(target);
    EventBus internalEventBus = new EventBus();
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    Supplier<Long> threadIdSupplier = BuckEventBus.getDefaultThreadIdSupplier();
    BuckEventBus eventBus = new BuckEventBus(internalEventBus, fakeClock, threadIdSupplier);
    eventBus.register(listener);

    eventBus.post(CommandEvent.started("party", true));
    eventBus.post(BuildEvent.started(buildTargets));
    eventBus.post(BuildRuleEvent.started(rule));
    eventBus.post(StepEvent.started(step, "fakeStep", "I'm a Fake Step!"));

    // Intentionally fire events out of order to verify sorting happens.
    BuckEvent stepFinished = StepEvent.finished(step, "fakeStep", "I'm a Fake Step!", 0);
    stepFinished.configure(fakeClock.currentTimeMillis(),
        fakeClock.nanoTime(),
        threadIdSupplier.get());


    BuckEvent ruleFinished = BuildRuleEvent.finished(
        rule,
        BuildRuleStatus.SUCCESS,
        CacheResult.MISS);
    ruleFinished.configure(fakeClock.currentTimeMillis(),
        fakeClock.nanoTime(),
        threadIdSupplier.get());

    internalEventBus.post(ruleFinished);
    internalEventBus.post(stepFinished);

    eventBus.post(BuildEvent.finished(buildTargets, 0));
    eventBus.post(CommandEvent.finished("party", true, 0));
    listener.outputTrace();

    File resultFile = new File(tmpDir.getRoot(), BuckConstant.BIN_DIR + "/build.trace");

    ObjectMapper mapper = new ObjectMapper();

    List<ChromeTraceEvent> resultMap = mapper.readValue(
        resultFile,
        new TypeReference<List<ChromeTraceEvent>>() {});

    assertEquals(8, resultMap.size());
    assertEquals("party", resultMap.get(0).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(0).getPhase());
    assertEquals("build", resultMap.get(1).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(1).getPhase());
    assertEquals("//fake:rule", resultMap.get(2).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(2).getPhase());
    assertEquals("fakeStep", resultMap.get(3).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(3).getPhase());
    assertEquals("fakeStep", resultMap.get(4).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(4).getPhase());
    assertEquals("//fake:rule", resultMap.get(5).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(5).getPhase());
    assertEquals("build", resultMap.get(6).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(6).getPhase());
    assertEquals("party", resultMap.get(7).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(7).getPhase());

    verify(context);
  }
}
