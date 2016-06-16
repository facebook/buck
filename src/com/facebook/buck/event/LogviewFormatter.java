/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.event;

import com.google.common.collect.ImmutableList;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Consolidates multi-line log messages to single-line that can be consumed by Logview.
 */
public class LogviewFormatter {

  private LogviewFormatter() {}

  public static Iterable<String> format(Throwable t) {
    StringBuilder sb = new StringBuilder();
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    sb.append(sw);
    return ImmutableList.of(sb.toString());
  }
}

