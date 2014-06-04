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

package com.facebook.buck.event;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;

public class MissingSymbolEvent extends AbstractBuckEvent {

  public enum SymbolType {
    Java
  }

  private final BuildTarget target;
  private final String symbol;
  private final SymbolType type;

  private MissingSymbolEvent(BuildTarget target, String symbol, SymbolType type) {
    this.target = Preconditions.checkNotNull(target);
    this.symbol = Preconditions.checkNotNull(symbol);
    this.type = Preconditions.checkNotNull(type);
  }

  public static MissingSymbolEvent create(
      BuildTarget target,
      String symbol,
      SymbolType type) {
    return new MissingSymbolEvent(target, symbol, type);
  }

  public BuildTarget getTarget() {
    return target;
  }

  public String getSymbol() {
    return symbol;
  }

  public SymbolType getType() {
    return type;
  }

  @Override
  public String getEventName() {
    return "MissingSymbolEvent";
  }

  @Override
  protected String getValueString() {
    return
        "Missing symbol: " + symbol;
  }

  @Override
  public boolean eventsArePair(BuckEvent event) {
    return false;
  }
}
