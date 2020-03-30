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

@BuckStyleValue
public abstract class ThriftOverHttpServiceConfig {

  public static final String DEFAULT_THRIFT_PATH = "/thrift";

  public abstract HttpService getService();

  public ThriftProtocol getThriftProtocol() {
    return ThriftProtocol.BINARY;
  }

  public abstract String getThriftPath();

  public static ThriftOverHttpServiceConfig of(HttpService service) {
    return of(service, DEFAULT_THRIFT_PATH);
  }

  public static ThriftOverHttpServiceConfig of(HttpService service, String thriftPath) {
    return ImmutableThriftOverHttpServiceConfig.of(service, thriftPath);
  }
}
