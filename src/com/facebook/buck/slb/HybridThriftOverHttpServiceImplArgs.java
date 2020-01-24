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

package com.facebook.buck.slb;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.util.concurrent.ListeningExecutorService;

@BuckStyleValue
public abstract class HybridThriftOverHttpServiceImplArgs {

  public static final String DEFAULT_HYBRID_THRIFT_PATH = "/hybrid_thrift";

  public abstract HttpService getService();

  public abstract ListeningExecutorService getExecutor();

  public abstract ThriftProtocol getThriftProtocol();

  public abstract String getHybridThriftPath();

  public static HybridThriftOverHttpServiceImplArgs of(
      HttpService service, ListeningExecutorService executor) {
    return of(service, executor, ThriftProtocol.COMPACT, DEFAULT_HYBRID_THRIFT_PATH);
  }

  public static HybridThriftOverHttpServiceImplArgs of(
      HttpService service,
      ListeningExecutorService executor,
      ThriftProtocol thriftProtocol,
      String hybridThriftPath) {
    return ImmutableHybridThriftOverHttpServiceImplArgs.of(
        service, executor, thriftProtocol, hybridThriftPath);
  }
}
