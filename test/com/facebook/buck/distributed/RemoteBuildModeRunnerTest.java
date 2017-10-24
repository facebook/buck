/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.command.Builder;
import com.google.common.collect.Lists;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class RemoteBuildModeRunnerTest {
  @Test
  public void testFinalBuildStatusIsSet() throws IOException, InterruptedException {
    int expectedExitCode = 4221;

    Builder builder = EasyMock.createMock(Builder.class);
    EasyMock.expect(
            builder.buildLocallyAndReturnExitCode(EasyMock.anyObject(), EasyMock.anyObject()))
        .andReturn(expectedExitCode)
        .once();
    RemoteBuildModeRunner.FinalBuildStatusSetter setter =
        EasyMock.createMock(RemoteBuildModeRunner.FinalBuildStatusSetter.class);
    setter.setFinalBuildStatus(EasyMock.eq(expectedExitCode));
    EasyMock.expectLastCall().once();
    EasyMock.replay(builder, setter);

    RemoteBuildModeRunner runner = new RemoteBuildModeRunner(builder, Lists.newArrayList(), setter);
    int actualExitCode = runner.runAndReturnExitCode();
    Assert.assertEquals(expectedExitCode, actualExitCode);

    EasyMock.verify(builder, setter);
  }
}
