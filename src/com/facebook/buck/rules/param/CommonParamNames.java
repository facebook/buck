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

package com.facebook.buck.rules.param;

/** Param names used in most rules. */
public class CommonParamNames {

  public static final ParamName NAME = ParamName.bySnakeCase("name");
  public static final ParamName DEPS = ParamName.bySnakeCase("deps");
  public static final ParamName TESTS = ParamName.bySnakeCase("tests");

  public static final ParamName COMPATIBLE_WITH = ParamName.bySnakeCase("compatible_with");
  public static final ParamName DEFAULT_TARGET_PLATFORM =
      ParamName.bySnakeCase("default_target_platform");
  public static final ParamName DEFAULT_HOST_PLATFORM =
      ParamName.bySnakeCase("default_host_platform");

  public static final ParamName LICENSES = ParamName.bySnakeCase("licenses");
  public static final ParamName LABELS = ParamName.bySnakeCase("labels");
  public static final ParamName CONTACTS = ParamName.bySnakeCase("contacts");

  public static final String VISIBILITY_NAME = "visibility";
  public static final String WITHIN_VIEW_NAME = "within_view";

  public static final ParamName VISIBILITY = ParamName.bySnakeCase(VISIBILITY_NAME);
  public static final ParamName WITHIN_VIEW = ParamName.bySnakeCase(WITHIN_VIEW_NAME);

  private CommonParamNames() {}
}
