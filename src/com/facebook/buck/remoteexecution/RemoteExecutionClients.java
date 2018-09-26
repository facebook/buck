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

package com.facebook.buck.remoteexecution;

import java.io.Closeable;

/**
 * A Remote Execution service consists of two primary things, an execution service and a CAS. To use
 * the service, we also need to ability to encode and decode various of the structures and this is
 * provided by the Protocol.
 */
public interface RemoteExecutionClients extends Closeable {
  RemoteExecutionService getRemoteExecutionService();

  ContentAddressedStorage getContentAddressedStorage();

  Protocol getProtocol();
}
