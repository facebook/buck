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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.message_ipc.Connection;
import com.facebook.buck.message_ipc.MessageSerializer;
import com.facebook.buck.message_ipc.MessageTransport;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.external.BundledExternalProcessLauncher;
import com.facebook.buck.worker.WorkerProcess;
import com.facebook.buck.worker.WorkerProcessParams;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class OutOfProcessConnectionFactory {

  private static final MessageSerializer MESSAGE_SERIALIZER = new MessageSerializer();

  private OutOfProcessConnectionFactory() {}

  private static final BundledExternalProcessLauncher LAUNCHER =
      new BundledExternalProcessLauncher();
  private static final Supplier<ImmutableList<String>> COMMAND_SUPPLIER =
      Suppliers.memoize(LAUNCHER::getCommandForOutOfProcessJavac);
  private static final Supplier<ImmutableMap<String, String>> ENV_SUPPLIER =
      Suppliers.memoize(LAUNCHER::getEnvForOutOfProcessJavac);

  @Nullable
  public static Connection<OutOfProcessJavacConnectionInterface> connectionForOutOfProcessBuild(
      ExecutionContext context, ProjectFilesystem filesystem, Javac javac, BuildTarget invokingRule)
      throws IOException, InterruptedException {
    Connection<OutOfProcessJavacConnectionInterface> connection = null;
    if (javac instanceof OutOfProcessJsr199Javac) {
      OutOfProcessJsr199Javac outOfProcessJsr199Javac = (OutOfProcessJsr199Javac) javac;
      Path relativeTmpDir =
          BuildTargets.getScratchPath(filesystem, invokingRule, "%s_oop_javac__tmp");
      filesystem.mkdirs(relativeTmpDir);

      WorkerProcessPoolFactory factory = new WorkerProcessPoolFactory(filesystem);
      WorkerProcessParams workerProcessParams =
          WorkerProcessParams.of(
              relativeTmpDir,
              COMMAND_SUPPLIER.get(),
              ENV_SUPPLIER.get(),
              Runtime.getRuntime().availableProcessors() / 4,
              Optional.empty());
      WorkerProcessPool processPool = factory.getWorkerProcessPool(context, workerProcessParams);
      WorkerProcess workerProcess = processPool.borrowWorkerProcess();

      MessageTransport transport =
          new MessageTransport(
              workerProcess,
              MESSAGE_SERIALIZER,
              () -> processPool.returnWorkerProcess(workerProcess));
      connection = new Connection<>(transport);
      connection.setRemoteInterface(
          OutOfProcessJavacConnectionInterface.class,
          OutOfProcessJdkProvidedInMemoryJavac.class.getClassLoader());
      outOfProcessJsr199Javac.setConnection(connection);
    }
    return connection;
  }
}
