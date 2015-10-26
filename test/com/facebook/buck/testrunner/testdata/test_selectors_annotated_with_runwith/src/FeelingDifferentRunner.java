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

package com.facebook.buck.junit.testdata.test_selectors_annotated_with_runwith.src;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class FeelingDifferentRunner extends BlockJUnit4ClassRunner {

  private static final Logger logger;

  static {
    String LOGGER_NAME = "AnotherRunnerLogger";
    logger = Logger.getLogger(LOGGER_NAME);
    try {
      FileHandler fileHandler = new FileHandler(LOGGER_NAME + ".log");
      fileHandler.setFormatter(new SimpleFormatter());
      logger.addHandler(fileHandler);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public FeelingDifferentRunner(Class<?> klass) throws InitializationError {
    super(klass);
  }

  @Override
  protected Statement methodBlock(FrameworkMethod method) {
    logger.severe(method.toString());
    return super.methodBlock(method);
  }
}
