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

package com.facebook.buck.event;

public abstract class DirCacheExperimentEvent extends AbstractBuckEvent {
  private final String name;
  private final String description;

  public static ReadWrite readWrite() {
    return new ReadWrite();
  }

  public static ReadOnly readOnly() {
    return new ReadOnly();
  }

  public static PropagateOnly propagateOnly() {
    return new PropagateOnly();
  }

  protected DirCacheExperimentEvent(String name, String description) {
    super(EventKey.unique());
    this.name = name;
    this.description = description;
  }

  @Override
  public String getEventName() {
    return name;
  }

  @Override
  public String getValueString() {
    return description;
  }

  /**
   * This event tells you dir cache is in traditional read-write operation mode.
   */
  public static class ReadWrite extends DirCacheExperimentEvent {
    public ReadWrite() {
      super("dirCacheReadWrite", "normal dir cache operation");
    }
  }

  /**
   * This event tells you dir cache is in traditional read only operation mode.
   */
  public static class ReadOnly extends DirCacheExperimentEvent {
    public ReadOnly() {
      super("dirCacheReadOnly", "readonly dir cache operation");
    }
  }

  /**
   * This event tells you dir cache is in experimental mode when it propagates artifacts only
   * from remote cache. Local artifacts are not stored.
   */
  public static class PropagateOnly extends DirCacheExperimentEvent {
    public PropagateOnly() {
      super("dirCacheIsPropagationOfRemoteCache", "dir cache only accepts propagation");
    }
  }
}
