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

package com.facebook.buck.event;

/** Event for sending Annotation Processing perf stats from the compilation step to be logged. */
public class KotlinPluginStatsEvent extends AbstractBuckEvent {

  private final String invokingRule;
  private final KotlinPluginPerfStats data;

  public KotlinPluginStatsEvent(String invokingRule, KotlinPluginPerfStats data) {
    super(EventKey.unique());
    this.invokingRule = invokingRule;
    this.data = data;
  }

  public KotlinPluginPerfStats getData() {
    return data;
  }

  public String getInvokingRule() {
    return invokingRule;
  }

  @Override
  protected String getValueString() {
    return "kaptPerfStats";
  }

  @Override
  public String getEventName() {
    return "KotlinPluginStatsEvent";
  }

  @Override
  public String toString() {
    return "KotlinPluginStatsEvent{"
        + "invokingRule='"
        + invokingRule
        + '\''
        + ", data="
        + data
        + '}';
  }
}
