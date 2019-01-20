/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.apple.project;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PrintStreamPathOutputPresenterTest {

  @Test
  public void testPresenterWithFull() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream sink = new PrintStream(baos);

    Path rootPath = Paths.get("root");
    PrintStreamPathOutputPresenter presenter =
        new PrintStreamPathOutputPresenter(sink, Mode.FULL, rootPath);

    presenter.present("my prefix", rootPath);
    String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    assertEquals("my prefix " + rootPath.resolve(rootPath) + System.lineSeparator(), content);
  }

  @Test
  public void testPresenterWithNonFull() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream sink = new PrintStream(baos);

    Path rootPath = Paths.get("/root");
    PrintStreamPathOutputPresenter presenter =
        new PrintStreamPathOutputPresenter(sink, Mode.SIMPLE, rootPath);

    presenter.present("my prefix", rootPath);
    String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    assertEquals("my prefix " + rootPath + System.lineSeparator(), content);
  }

  @Test
  public void testPresenterWithNonNonde() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream sink = new PrintStream(baos);

    Path rootPath = Paths.get("/root");
    PrintStreamPathOutputPresenter presenter =
        new PrintStreamPathOutputPresenter(sink, Mode.NONE, rootPath);

    presenter.present("my prefix", rootPath);
    String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    assertEquals("", content);
  }
}
