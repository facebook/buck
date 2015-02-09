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

package com.facebook.buck.apple.graphql;

public enum GraphQLGenerationMode {
  /**
   * Generates a graphql data model suitable to compile against. All symbols will be declared but
   * might not be defined.
   */
  FOR_COMPILING,

  /**
   * Generates a graphql data model suitable to link against. All symbols will be both declared
   * and defined.
   */
  FOR_LINKING,
  ;
}
