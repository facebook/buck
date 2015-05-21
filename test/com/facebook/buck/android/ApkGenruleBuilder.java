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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class ApkGenruleBuilder extends AbstractNodeBuilder<ApkGenruleDescription.Arg> {

  private ApkGenruleBuilder(BuildTarget target) {
    super(new ApkGenruleDescription(), target);
  }

  public static ApkGenruleBuilder create(BuildTarget target) {
    return new ApkGenruleBuilder(target);
  }

  public ApkGenruleBuilder setOut(String out) {
    arg.out = out;
    return this;
  }

  public ApkGenruleBuilder setBash(String bash) {
    arg.bash = Optional.of(bash);
    return this;
  }

  public ApkGenruleBuilder setCmd(String cmd) {
    arg.cmd = Optional.of(cmd);
    return this;
  }

  public ApkGenruleBuilder setCmdExe(String cmdExe) {
    arg.cmdExe = Optional.of(cmdExe);
    return this;
  }

  public ApkGenruleBuilder setSrcs(ImmutableList<SourcePath> srcs) {
    arg.srcs = Optional.of(srcs);
    return this;
  }

  public ApkGenruleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

  public ApkGenruleBuilder setApk(BuildTarget apk) {
    arg.apk = apk;
    return this;
  }

}
