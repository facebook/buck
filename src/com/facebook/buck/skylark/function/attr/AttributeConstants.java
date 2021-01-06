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

package com.facebook.buck.skylark.function.attr;

/** Container class for various constant strings used in Skylark annotations */
class AttributeConstants {
  static final String DEFAULT_PARAM_NAME = "default";
  static final String DEFAULT_PARAM_DOC = "The default value for this parameter";

  static final String MANDATORY_PARAM_NAME = "mandatory";
  static final String MANDATORY_PARAM_DOC = "Whether this parameter is mandatory";
  static final String MANDATORY_PARAM_DEFAULT_VALUE = "False";

  static final String DOC_PARAM_NAME = "doc";
  static final String DOC_PARAM_DOC = "The docstring for this parameter";
  static final String DOC_PARAM_DEFAULT_VALUE = "''";

  static final String VALUES_PARAM_NAME = "values";
  static final String VALUES_PARAM_DOC =
      "A list of valid values for this parameter. If empty, all values are allowed";
  static final String VALUES_PARAM_DEFAULT_VALUE = "[]";
}
