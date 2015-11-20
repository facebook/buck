/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.util.concurrent.LinkedBlockingStack;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public enum WorkQueueExecutionOrder {

  FIFO {
    @Override
    public BlockingQueue<Runnable> newWorkQueue() {
      return new LinkedBlockingQueue<>();
    }
  },

  LIFO {
    @Override
    public BlockingQueue<Runnable> newWorkQueue() {
      return new LinkedBlockingStack<>();
    }
  },

  ;

  public abstract BlockingQueue<Runnable> newWorkQueue();
}
