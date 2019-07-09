/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.analysis.impl.RuleAnalysisContextImpl;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.AbstractFakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SkylarkRuleContextActionsTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private RuleAnalysisContextImpl context;

  @Before
  public void setUp() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    this.context =
        new RuleAnalysisContextImpl(
            target,
            ImmutableMap.of(),
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
            new DefaultBuckEventBus(AbstractFakeClock.doNotCare(), new BuildId("1234-5678")));
  }

  @Test
  public void writingAFileAddsToListOfOutputs() throws EvalException {

    CapturingActionRegistry registry = new CapturingActionRegistry(context.actionRegistry());
    SkylarkRuleContextActions actions = new SkylarkRuleContextActions(registry);
    Artifact artifact = actions.declareFile("bar.sh", Location.BUILTIN);
    actions.write(artifact, "contents", false, Location.BUILTIN);

    assertEquals(ImmutableSet.of(artifact), registry.getOutputs());
  }
}
