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

package com.facebook.buck.step.external.executor;

import com.facebook.buck.shell.WorkerProcessProtocol;
import com.facebook.buck.shell.WorkerProcessProtocolZero;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicInteger;

/** Entry point to out-of-process step executor */
public class ExternalStepExecutorMain {

  public static void main(String[] args) {
    JsonWriter processStdinWriter = new JsonWriter(new OutputStreamWriter(System.out));
    JsonReader processStdoutReader = new JsonReader(new InputStreamReader(System.in));
    AtomicInteger messageCounter = new AtomicInteger();

    try {
      WorkerProcessProtocol workerProcessProtocol = null;
      try {
        workerProcessProtocol =
            new WorkerProcessProtocolZero(processStdinWriter, processStdoutReader);

        workerProcessProtocol.sendHandshake(messageCounter.getAndIncrement());
        workerProcessProtocol.receiveHandshake(0);
        while (true) {
          if (JsonToken.END_ARRAY == processStdoutReader.peek()) {
            workerProcessProtocol.close();
            break;
          }
          int messageId = messageCounter.getAndIncrement();
          workerProcessProtocol.receiveCommand(messageId);
          workerProcessProtocol.sendCommandResponse(messageId, "result", 0);
        }
      } finally {
        if (workerProcessProtocol != null) {
          workerProcessProtocol.close();
        }
      }
    } catch (IOException e) {
      System.exit(1);
    }
  }
}
