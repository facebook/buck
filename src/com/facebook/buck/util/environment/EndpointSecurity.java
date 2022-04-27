/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.util.environment;

import com.google.common.collect.ImmutableMap;

/** EndpointSecurity provides the utilitiy functions to get information about security controls */
public interface EndpointSecurity {

  /** getEdrInfo returns K-V string pairs about installed EDRs and their versions */
  ImmutableMap<String, String> getEdrInfo();
}
