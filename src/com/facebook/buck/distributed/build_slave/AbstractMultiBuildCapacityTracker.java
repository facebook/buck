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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.core.util.log.Logger;
import java.io.IOException;

/**
 * Abstract implementation of a {@link CapacityTracker} that is used when there're multiple builds
 * stacked on the host. It communicates with the build slave process to track available capacity.
 */
public abstract class AbstractMultiBuildCapacityTracker implements CapacityTracker {
  protected final Logger LOG = Logger.get(getClass());

  protected final CapacityService service;
  protected final int maxAvailableCapacity;

  public AbstractMultiBuildCapacityTracker(CapacityService service, int maxAvailableCapacity) {
    this.service = service;
    this.maxAvailableCapacity = maxAvailableCapacity;
  }

  @Override
  public int getMaxAvailableCapacity() {
    return maxAvailableCapacity;
  }

  @Override
  public void returnCapacity() {
    returnCapacity(1);
  }

  /** Returns back obtained capacity */
  protected boolean returnCapacity(int size) {
    boolean success = true;
    try {
      service.returnCapacity(size);
    } catch (IOException e) {
      success = false;
    }
    return success;
  }
}
