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

package com.facebook.buck.logd.client;

import com.google.rpc.Status;
import io.grpc.stub.StreamObserver;

/** A StreamObserverFactory interface */
public interface StreamObserverFactory {

  /**
   * Creates a stream observer that observes and processes server responses corresponding with
   * provided log file path
   *
   * @param path path of log file to which logD is streaming logs to
   * @return a StreamObserver that observes and processes server responses corresponding with
   *     provided log file path
   */
  StreamObserver<Status> createStreamObserver(String path);
}
