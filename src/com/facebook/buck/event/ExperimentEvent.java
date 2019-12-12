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

import java.util.Optional;
import javax.annotation.Nullable;

public class ExperimentEvent extends AbstractBuckEvent {
  private final String tag;
  private final String variant;
  private final String propertyName;
  private final Optional<Long> value;
  private final Optional<String> content;

  public ExperimentEvent(
      String tag,
      String variant,
      String propertyName,
      @Nullable Long value,
      @Nullable String content) {
    super(EventKey.unique());
    this.tag = tag;
    this.variant = variant;
    this.propertyName = propertyName;
    this.value = Optional.ofNullable(value);
    this.content = Optional.ofNullable(content);
  }

  @Override
  public String getEventName() {
    return tag;
  }

  @Override
  protected String getValueString() {
    return "";
  }

  public String getTag() {
    return tag;
  }

  public String getVariant() {
    return variant;
  }

  public String getPropertyName() {
    return propertyName;
  }

  public Optional<Long> getValue() {
    return value;
  }

  public Optional<String> getContent() {
    return content;
  }
}
