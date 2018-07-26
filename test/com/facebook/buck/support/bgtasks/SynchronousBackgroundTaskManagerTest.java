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

package com.facebook.buck.support.bgtasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import org.junit.Before;
import org.junit.Test;

public class SynchronousBackgroundTaskManagerTest {

  private SynchronousBackgroundTaskManager manager;

  @Before
  public void setUp() {
    manager = new SynchronousBackgroundTaskManager(false);
  }

  @Test
  public void testScheduleCreatesManagedTask() {
    BackgroundTask<FakeArgs> task =
        ImmutableBackgroundTask.<FakeArgs>builder()
            .setAction(new FakeAction())
            .setActionArgs(new FakeArgs(true))
            .build();
    String taskId = manager.schedule(task, "testTask");

    assertTrue(taskId.contains("testTask"));
    assertEquals(manager.getScheduledTasks().get(0).getTask(), task);
  }

  @Test
  public void testRunSuccessPath() {
    BackgroundTask<FakeArgs> task =
        ImmutableBackgroundTask.<FakeArgs>builder()
            .setAction(new FakeAction())
            .setActionArgs(new FakeArgs(true))
            .build();
    manager.schedule(task, "successTask");
    manager.notify(Notification.COMMAND_END);

    assertEquals(task.getActionArgs().output, "succeeded");
    assertEquals(manager.getScheduledTasks().size(), 0);
    assertEquals(manager.getFinishedTasks().size(), 1);
  }

  @Test
  public void testRunFailurePath() {
    BackgroundTask<FakeArgs> task =
        ImmutableBackgroundTask.<FakeArgs>builder()
            .setAction(new FakeAction())
            .setActionArgs(new FakeArgs(false))
            .build();
    manager.schedule(task, "failureTask");
    manager.notify(Notification.COMMAND_END);

    assertEquals(task.getActionArgs().output, "init");
    assertEquals(manager.getScheduledTasks().size(), 0);
    assertEquals(manager.getFinishedTasks().size(), 1);
  }

  static class FakeAction implements TaskAction<FakeArgs> {

    @Override
    public void run(FakeArgs args) throws Exception {
      if (args.getSuccess()) {
        args.setOutput("succeeded");
      } else {
        throw new Exception("failed");
      }
    }
  }

  static class FakeArgs {
    private boolean success;
    private String output;

    public FakeArgs(boolean success) {
      this.success = success;
      this.output = "init";
    }

    public boolean getSuccess() {
      return success;
    }

    public String getOutput() {
      return output;
    }

    public void setOutput(String newOutput) {
      output = newOutput;
    }
  }
}
