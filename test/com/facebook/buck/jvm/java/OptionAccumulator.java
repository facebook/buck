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

package com.facebook.buck.jvm.java;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class OptionAccumulator implements AbstractJavacOptions.OptionsConsumer {
  public final Map<String, String> keyVals = new HashMap<String, String>();
  public final List<String> flags = new ArrayList<String>();
  public final List<String> extras = new ArrayList<String>();

  @Override
  public void addOptionValue(String option, String value) {
    keyVals.put(option, value);
  }

  @Override
  public void addFlag(String flagName) {
    flags.add(flagName);
  }

  @Override
  public void addExtras(Collection<String> extraArgs) {
    extras.addAll(extraArgs);
  }
}
