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


package com.facebook.buck.android;

/**
 * This list of package types is taken from the set of targets that the default build.xml provides
 * for Android projects.
 *
 * <p>Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
 */
enum PackageType {
  DEBUG,
  INSTRUMENTED,
  RELEASE,
  TEST,
  ;
}
