/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util.concurrent;

/**
 * Amalgamation of parameters that control how many jobs we can run at once.
 */
public class ConcurrencyLimit {

  public final int threadLimit;
  public final double loadLimit;

  public ConcurrencyLimit(int threadLimit, double loadLimit) {
    this.threadLimit = threadLimit;
    this.loadLimit = loadLimit;
  }
}
