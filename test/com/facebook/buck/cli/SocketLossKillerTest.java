/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.AnnotatedRunnable;
import com.facebook.buck.testutil.FakeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SocketLossKillerTest {

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private final FakeExecutor fakeExecutor = new FakeExecutor();

  private Path socketPath;

  @Before
  public void setUp() throws IOException {
    socketPath = tempFolder.newFile("socket").toPath();
  }

  @Test
  public void callingArmSetsUpPeriodicTask() {
    SocketLossKiller socketLossKiller = new SocketLossKiller(fakeExecutor, socketPath, () -> {});
    socketLossKiller.arm();
    assertThat(fakeExecutor.getRunnableList(), hasSize(1));
    AnnotatedRunnable annotatedRunnable = fakeExecutor.getRunnableList().get(0);
    assertThat(annotatedRunnable.getInitDelay(), equalTo(0L));
    assertThat(annotatedRunnable.getDelay(), equalTo(5000L));
    assertThat(annotatedRunnable.getUnit(), equalTo(TimeUnit.MILLISECONDS));
  }

  @Test
  public void repeatedCallsToArmAreNoOp() {
    SocketLossKiller socketLossKiller = new SocketLossKiller(fakeExecutor, socketPath, () -> {});
    socketLossKiller.arm();
    assertThat(
        "check task is scheduled when arm is called for the first time",
        fakeExecutor.getRunnableList(),
        hasSize(1));
    socketLossKiller.arm();
    assertThat(
        "check task should not be scheduled again when arm is called a second time",
        fakeExecutor.getRunnableList(),
        hasSize(1));
  }

  @Test
  public void killActionShouldRunIfSocketIsDeleted() throws IOException {
    CountingKillAction action = new CountingKillAction();
    SocketLossKiller socketLossKiller = new SocketLossKiller(fakeExecutor, socketPath, action);
    socketLossKiller.arm();
    assertThat(fakeExecutor.getRunnableList(), hasSize(1));
    AnnotatedRunnable checkAction = fakeExecutor.getRunnableList().get(0);
    checkAction.run();
    assertThat(
        "socket should still be valid, so kill action should not be called.",
        action.getTimesCalled(),
        equalTo(0));
    Files.delete(socketPath);
    checkAction.run();
    assertThat(
        "socket should now be deleted, so kill action should be called.",
        action.getTimesCalled(),
        equalTo(1));
  }

  @Test
  public void killActionShouldRunIfSocketIsDeletedAndRecreated()
      throws IOException, InterruptedException {
    CountingKillAction action = new CountingKillAction();
    SocketLossKiller socketLossKiller = new SocketLossKiller(fakeExecutor, socketPath, action);
    socketLossKiller.arm();
    assertThat(fakeExecutor.getRunnableList(), hasSize(1));
    AnnotatedRunnable checkAction = fakeExecutor.getRunnableList().get(0);
    checkAction.run();
    assertThat(
        "socket should still be valid, so kill action should not be called.",
        action.getTimesCalled(),
        equalTo(0));

    Files.delete(socketPath);
    // Sleep for at least 2 seconds so creation time can update, since file creation time has second
    // precision on linux.  This is a bit of a hack, but realistically the socket file will not be
    // deleted and re-created in quick succession.
    Thread.sleep(2000);
    Files.createFile(socketPath);

    checkAction.run();
    assertThat(
        "socket should be a different file, so kill action should be called.",
        action.getTimesCalled(),
        equalTo(1));
  }

  private static final class CountingKillAction implements Runnable {
    private int timesCalled = 0;

    public int getTimesCalled() {
      return timesCalled;
    }

    @Override
    public void run() {
      timesCalled++;
    }
  }
}
