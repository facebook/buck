/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.util.List;

import javax.annotation.Nullable;

public class AuditRulesOptions extends AbstractCommandOptions {

  @Option(name = "--type",
      aliases = { "-t" },
      usage = "The types of rule to filter by")
  @Nullable
  private List<String> types = null;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  AuditRulesOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public List<String> getArguments() {
    return arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types == null ? ImmutableSet.<String>of() : ImmutableSet.copyOf(types);
  }
}
