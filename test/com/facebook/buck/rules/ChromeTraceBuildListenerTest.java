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

import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.FakeStep;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ChromeTraceBuildListenerTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testBuildJson() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(projectFilesystem);

    BuildRule rule = new FakeBuildRule(
        new BuildRuleType("fakeRule"),
        BuildTargetFactory.newInstance("//fake:rule"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    FakeStep step = new FakeStep("fakeStep", "I'm a Fake Step!", 0);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableSet<BuildRule> buildRules = ImmutableSet.of(rule);

    listener.buildStarted(BuildEvent.started(buildRules));
    listener.ruleStarted(BuildRuleEvent.started(rule));
    listener.stepStarted(StepEvent.started(step, "fakeStep", "I'm a Fake Step!"));
    listener.stepFinished(StepEvent.finished(step, "fakeStep", "I'm a Fake Step!", 0));
    listener.ruleFinished(BuildRuleEvent.finished(rule, BuildRuleStatus.SUCCESS, CacheResult.MISS));
    listener.buildFinished(BuildEvent.finished(buildRules, 0));

    File resultFile = new File(tmpDir.getRoot(), BuckConstant.BIN_DIR + "/build.trace");

    ObjectMapper mapper = new ObjectMapper();

    List<ChromeTraceEvent> resultMap = mapper.readValue(
        resultFile,
        new TypeReference<List<ChromeTraceEvent>>() {});

    assertEquals(6, resultMap.size());
    assertEquals("build", resultMap.get(0).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(0).getPhase());
    assertEquals("//fake:rule", resultMap.get(1).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(1).getPhase());
    assertEquals("fakeStep", resultMap.get(2).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(2).getPhase());
    assertEquals("fakeStep", resultMap.get(3).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(3).getPhase());
    assertEquals("//fake:rule", resultMap.get(4).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(4).getPhase());
    assertEquals("build", resultMap.get(5).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(5).getPhase());

    verify(context);
  }
}
