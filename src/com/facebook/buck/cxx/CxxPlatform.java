/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;

/**
 * Interface describing a C/C++ toolchain and platform to build for.
 */
public interface CxxPlatform {

  String getName();

  SourcePath getAs();
  ImmutableList<String> getAsflags();

  SourcePath getAspp();
  ImmutableList<String> getAsppflags();

  SourcePath getCc();
  ImmutableList<String> getCflags();

  SourcePath getCxx();
  ImmutableList<String> getCxxflags();

  SourcePath getCpp();
  ImmutableList<String> getCppflags();

  SourcePath getCxxpp();
  ImmutableList<String> getCxxppflags();

  SourcePath getCxxld();
  ImmutableList<String> getCxxldflags();

  Linker getLd();
  ImmutableList<String> getLdflags();

  SourcePath getAr();
  ImmutableList<String> getArflags();

  SourcePath getLex();
  ImmutableList<String> getLexFlags();
  BuildTarget getLexDep();

  SourcePath getYacc();
  ImmutableList<String> getYaccFlags();

  BuildTarget getPythonDep();

  BuildTarget getGtestDep();

  BuildTarget getBoostTestDep();

}
