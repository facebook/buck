/*
 * Copyright 2019-present Facebook, Inc.
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

import java.util.Optional;

/**
 * This is the main entry point for running buck without buckd.
 *
 * <p>This main will take care of initializing all the state that buck needs given that the buck
 * state is not stored. It will also take care of error handling and shutdown procedures given that
 * we are not running as a nailgun server.
 */
public class MainWithoutNailgun {

  /**
   * The entry point of a buck command.
   *
   * @param args
   */
  public static void main(String[] args) {
    // TODO(bobyf): add shutdown handling
    new MainRunner(System.out, System.err, System.in, Optional.empty())
        .runMainThenExit(args, System.nanoTime());
  }
}
