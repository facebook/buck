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

package com.facebook.buck.parser.implicit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImplicitIncludePathTest {
  @Test
  public void parse() {
    assertEquals(
        "foo//bar/baz.bzl", ImplicitIncludePath.parse("foo//bar/baz.bzl").reconstructWithSlash());
    assertEquals(
        "foo//bar/baz.bzl", ImplicitIncludePath.parse("@foo//bar/baz.bzl").reconstructWithSlash());
    assertEquals(
        "foo//bar/baz/qux.bzl",
        ImplicitIncludePath.parse("foo//bar/baz:qux.bzl").reconstructWithSlash());
    assertEquals(
        "//BUILD_MODE.bzl", ImplicitIncludePath.parse("//:BUILD_MODE.bzl").reconstructWithSlash());

    assertEquals(
        "foo//bar:baz.bzl", ImplicitIncludePath.parse("foo//bar/baz.bzl").reconstructWithColon());
    assertEquals(
        "foo//bar:baz.bzl", ImplicitIncludePath.parse("@foo//bar/baz.bzl").reconstructWithColon());
    assertEquals(
        "foo//bar/baz:qux.bzl",
        ImplicitIncludePath.parse("foo//bar/baz:qux.bzl").reconstructWithColon());
    assertEquals(
        "//:BUILD_MODE.bzl", ImplicitIncludePath.parse("//:BUILD_MODE.bzl").reconstructWithColon());
  }
}
