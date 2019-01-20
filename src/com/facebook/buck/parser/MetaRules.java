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
package com.facebook.buck.parser;

/**
 * Contains information about meta rules (rules that are created by Buck to pass internal
 * information about parsing process)
 */
public class MetaRules {

  /**
   * Key of the meta-rule that lists the build files executed while reading rules. The value is a
   * list of strings with the root build file as the head and included build files as the tail, for
   * example: {"__includes":["/foo/BUCK", "/foo/buck_includes"]}
   */
  public static final String INCLUDES = "__includes";

  public static final String CONFIGS = "__configs";

  public static final String ENV = "__env";

  private MetaRules() {}
}
