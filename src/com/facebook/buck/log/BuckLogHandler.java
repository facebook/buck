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

package com.facebook.buck.log;

import com.facebook.buck.util.BuckConstant;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.LogManager;

/**
 * A subclass of {@link FileHandler} using a predefined pattern to determine the Buck output logs.
 */
public class BuckLogHandler extends FileHandler {

  private static final String CLASS_NAME = BuckLogHandler.class.getName();

  public BuckLogHandler() throws IOException {
    super(getPattern(), getLimit(), getCount());
  }

  // The reason for replicating some of FileHandler.configure logic below is that it doesn't
  // allow us to specify a default pattern in code (anything that's passed into the c'tor ends up
  // overriding values specified in bucklogging.properties).
  private static String getPattern() {
    LogManager manager = LogManager.getLogManager();
    String pattern = manager.getProperty(CLASS_NAME + ".pattern");
    if (pattern == null) {
      return BuckConstant.getLogPath().resolve("buck-%g.log").toString();
    }
    return pattern.trim();
  }

  private static int getIntProperty(String property, int defaultValue) {
    LogManager manager = LogManager.getLogManager();
    String limitString = manager.getProperty(CLASS_NAME + property);
    try {
      return Integer.parseInt(limitString.trim());
    } catch (Exception ex) {
      return defaultValue;
    }
  }

  private static int getLimit() {
    return Math.max(0, getIntProperty(".limit", 0));
  }

  private static int getCount() {
    return Math.max(1, getIntProperty(".count", 1));
  }

}
