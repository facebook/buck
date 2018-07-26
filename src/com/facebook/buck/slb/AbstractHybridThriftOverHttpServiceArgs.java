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

package com.facebook.buck.slb;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHybridThriftOverHttpServiceArgs {

  public static final String DEFAULT_HYBRID_THRIFT_PATH = "/hybrid_thrift";

  @Value.Parameter
  public abstract HttpService getService();

  @Value.Parameter
  public abstract ListeningExecutorService getExecutor();

  @Value.Default
  public ThriftProtocol getThriftProtocol() {
    return ThriftProtocol.COMPACT;
  }

  @Value.Default
  public String getHybridThriftPath() {
    return DEFAULT_HYBRID_THRIFT_PATH;
  }
}
