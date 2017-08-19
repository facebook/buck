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

package com.facebook.buck.oop_javac;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.jvm.java.OutOfProcessJavacConnectionInterface;
import com.facebook.buck.message_ipc.InvocationMessage;
import com.facebook.buck.message_ipc.MessageSerializer;
import com.facebook.buck.message_ipc.ReturnResultMessage;
import com.facebook.buck.util.Console;
import com.facebook.buck.worker.WorkerProcessCommand;
import com.facebook.buck.worker.WorkerProcessProtocol;
import com.facebook.buck.worker.WorkerProcessProtocolZero;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

public class MainOutOfProcessJavac implements AutoCloseable {

  private WorkerProcessProtocol.CommandReceiver protocol;
  private final AtomicInteger currentMessageID = new AtomicInteger();
  private final MessageSerializer messageSerializer = new MessageSerializer();
  private final OutOfProcessInvocationReceiver receiver;
  private boolean handshakePerformed = false;

  public MainOutOfProcessJavac() {
    this.receiver = new OutOfProcessInvocationReceiver(Console.createNullConsole());

    this.protocol = new WorkerProcessProtocolZero.CommandReceiver(System.out, System.in);
  }

  public void ensureHandshake() throws IOException {
    if (handshakePerformed) {
      return;
    }
    int messageID = currentMessageID.getAndAdd(1);
    protocol.handshake(messageID);
    handshakePerformed = true;
  }

  public void waitForJobAndExecute()
      throws IOException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
    int messageID = currentMessageID.getAndAdd(1);
    WorkerProcessCommand command = protocol.receiveCommand(messageID);
    InvocationMessage message = getInvocationMessage(command);
    Object returnResult = invokeInvocationAndGetReturnResult(message);
    ReturnResultMessage returnResultMessage = new ReturnResultMessage(returnResult);
    sendResultMessage(command, returnResultMessage);
    protocol.sendResponse(messageID, "result", 0);
  }

  private void sendResultMessage(
      WorkerProcessCommand command, ReturnResultMessage returnResultMessage) throws IOException {
    String serializedReturn = messageSerializer.serializeResult(returnResultMessage);
    Files.write(command.getStdOutPath(), serializedReturn.getBytes(UTF_8));
  }

  private InvocationMessage getInvocationMessage(WorkerProcessCommand command) throws IOException {
    String contents = new String(Files.readAllBytes(command.getArgsPath()), UTF_8);
    return messageSerializer.deserializeInvocation(contents);
  }

  private Object invokeInvocationAndGetReturnResult(InvocationMessage message)
      throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    Method[] methods = OutOfProcessJavacConnectionInterface.class.getMethods();
    Method methodToInvoke = null;
    for (Method m : methods) {
      if (m.getName().equals(message.getMethodName())) {
        methodToInvoke = m;
        break;
      }
    }
    if (methodToInvoke == null) {
      throw new NoSuchMethodException(
          String.format("Cannot find method to invoke with name %s", message.getMethodName()));
    }
    return methodToInvoke.invoke(receiver, message.getArguments().toArray());
  }

  @Override
  public void close() throws Exception {
    protocol.close();
  }
}
