/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.message_ipc;

import static org.junit.Assert.assertThat;

import com.facebook.buck.shell.FakeWorkerProcess;
import com.facebook.buck.shell.WorkerJobResult;
import com.facebook.buck.shell.WorkerProcess;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ConnectionTest {

  private interface RemoteInterface {
    String doString(String arg);

    int doInt(int arg);

    boolean doBoolean(String arg1, double arg2);
  }

  @Test
  public void testConnection() throws Exception {
    MessageSerializer messageSerializer = new MessageSerializer();
    WorkerProcess workerProcess =
        new FakeWorkerProcess(
            ImmutableMap.of(
                "{\"type\":\"InvocationMessage\",\"name\":\"doString\",\"args\":[\"input\"]}",
                WorkerJobResult.of(
                    0,
                    Optional.of("{\"type\":\"ReturnResultMessage\",\"value\":\"output\"}"),
                    Optional.empty()),
                "{\"type\":\"InvocationMessage\",\"name\":\"doInt\",\"args\":[4]}",
                WorkerJobResult.of(
                    0,
                    Optional.of("{\"type\":\"ReturnResultMessage\",\"value\":42}"),
                    Optional.empty())));
    workerProcess.ensureLaunchAndHandshake();
    MessageTransport messageTransport = new MessageTransport(workerProcess, messageSerializer);

    try (Connection<RemoteInterface> connection = new Connection<>(messageTransport)) {
      connection.setRemoteInterface(RemoteInterface.class, RemoteInterface.class.getClassLoader());

      String result = connection.getRemoteObjectProxy().doString("input");
      assertThat(result, Matchers.equalTo("output"));

      int intResult = connection.getRemoteObjectProxy().doInt(4);
      assertThat(intResult, Matchers.equalTo(42));
    }
  }

  @Test
  public void testConnectionWithMultipleArgs() throws Exception {
    MessageSerializer messageSerializer = new MessageSerializer();
    WorkerProcess workerProcess =
        new FakeWorkerProcess(
            ImmutableMap.of(
                "{\"type\":\"InvocationMessage\",\"name\":\"doBoolean\",\"args\":[\"input\",42.1234]}",
                WorkerJobResult.of(
                    0,
                    Optional.of("{\"type\":\"ReturnResultMessage\",\"value\":false}"),
                    Optional.empty())));
    workerProcess.ensureLaunchAndHandshake();
    MessageTransport messageTransport = new MessageTransport(workerProcess, messageSerializer);

    try (Connection<RemoteInterface> connection = new Connection<>(messageTransport)) {
      connection.setRemoteInterface(RemoteInterface.class, RemoteInterface.class.getClassLoader());

      boolean result = connection.getRemoteObjectProxy().doBoolean("input", 42.1234);
      assertThat(result, Matchers.equalTo(false));
    }
  }

  @Test
  public void testPassingNull() throws Exception {
    MessageSerializer messageSerializer = new MessageSerializer();
    WorkerProcess workerProcess =
        new FakeWorkerProcess(
            ImmutableMap.of(
                "{\"type\":\"InvocationMessage\",\"name\":\"doBoolean\",\"args\":[null,42.1234]}",
                WorkerJobResult.of(
                    0,
                    Optional.of("{\"type\":\"ReturnResultMessage\",\"value\":true}"),
                    Optional.empty())));
    workerProcess.ensureLaunchAndHandshake();
    MessageTransport messageTransport = new MessageTransport(workerProcess, messageSerializer);

    try (Connection<RemoteInterface> connection = new Connection<>(messageTransport)) {
      connection.setRemoteInterface(RemoteInterface.class, RemoteInterface.class.getClassLoader());

      boolean result = connection.getRemoteObjectProxy().doBoolean(null, 42.1234);
      assertThat(result, Matchers.equalTo(true));
    }
  }
}
