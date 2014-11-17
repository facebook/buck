/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.util.Collections;
import java.util.concurrent.ConcurrentMap;

/**
 * An association between the current thread and a given command.
 */
public class CommandThreadAssociation {

  private final String commandId;
  private final ConcurrentMap<Long, String> threadIdToCommandId;

  public CommandThreadAssociation(String commandId) {
    this(commandId, GlobalState.THREAD_ID_TO_COMMAND_ID);
  }

  @VisibleForTesting
  CommandThreadAssociation(
      String commandId,
      ConcurrentMap<Long, String> threadIdToCommandId) {
    this.commandId = commandId;
    this.threadIdToCommandId = threadIdToCommandId;
    String oldCommandId = threadIdToCommandId.put(Thread.currentThread().getId(), commandId);
    // We better not have overwritten an old mapping.
    Preconditions.checkState(oldCommandId == null);
  }

  public void stop() {
    // remove(commandId) would only remove the first association.
    boolean removed = threadIdToCommandId.values().removeAll(
        Collections.singleton(commandId));
    // We better have removed something, or we messed up.
    Preconditions.checkState(removed);
  }
}
