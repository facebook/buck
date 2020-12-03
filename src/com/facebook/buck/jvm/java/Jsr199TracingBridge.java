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

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.api.BuckTracingInterface;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

public class Jsr199TracingBridge implements BuckTracingInterface {
  private final JavacEventSink eventSink;
  private String buildTargetName;

  public Jsr199TracingBridge(JavacEventSink eventSink, String buildTargetName) {
    this.eventSink = eventSink;
    this.buildTargetName = buildTargetName;
  }

  @Override
  public void begin(String pluginName, String eventName, Map<String, String> args) {
    eventSink.reportCompilerPluginStarted(
        buildTargetName, pluginName, eventName, ImmutableMap.copyOf(args));
  }

  @Override
  public void end(Map<String, String> args) {
    eventSink.reportCompilerPluginFinished(buildTargetName, ImmutableMap.copyOf(args));
  }

  public void setBuildTargetName(String buildTargetName) {
    this.buildTargetName = buildTargetName;
  }
}
