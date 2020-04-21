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

package com.facebook.buck.rules.param;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParamNameTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void bySnakeCase() {
    ParamName p = ParamName.bySnakeCase("aaa_bbb");
    assertEquals("aaa_bbb", p.getSnakeCase());
    assertEquals("aaaBbb", p.getCamelCase());

    assertSame(ParamName.bySnakeCase("aaa_bbb"), p);
    assertSame(ParamName.byCamelCase("aaaBbb"), p);
  }

  @Test
  public void bySnakeCaseSimple() {
    ParamName p = ParamName.bySnakeCase("aaa");
    assertEquals("aaa", p.getSnakeCase());
    assertEquals("aaa", p.getCamelCase());

    assertSame(ParamName.bySnakeCase("aaa"), p);
    assertSame(ParamName.byCamelCase("aaa"), p);
  }

  @Test
  public void bySnakeCaseNotSnakeCase() {
    thrown.expect(IllegalArgumentException.class);

    ParamName.bySnakeCase("camelCase");
  }

  @Test
  public void byCamelCase() {
    ParamName p = ParamName.bySnakeCase("ccc_ddd");
    assertEquals("ccc_ddd", p.getSnakeCase());
    assertEquals("cccDdd", p.getCamelCase());

    assertSame(ParamName.bySnakeCase("ccc_ddd"), p);
    assertSame(ParamName.byCamelCase("cccDdd"), p);
  }

  @Test
  public void byCamelCaseSimple() {
    ParamName p = ParamName.bySnakeCase("ccc");
    assertEquals("ccc", p.getSnakeCase());
    assertEquals("ccc", p.getCamelCase());

    assertSame(ParamName.bySnakeCase("ccc"), p);
    assertSame(ParamName.byCamelCase("ccc"), p);
  }

  @Test
  public void byCamelCaseNotCamelCase() {
    thrown.expect(IllegalArgumentException.class);

    ParamName.byCamelCase("snake_case");
  }

  @Test
  public void bySnakeCaseStartsWithUnderscore() {
    // special handling of underscore-prefixed UDR param names
    assertEquals("_s_u", ParamName.bySnakeCase("_s_u").getSnakeCase());
    assertEquals("_sU", ParamName.byCamelCase("_sU").getCamelCase());
  }

  @Test
  public void byCamelCaseStartsWithUnderscore() {
    // special handling of underscore-prefixed UDR param names
    assertEquals("_sV", ParamName.byCamelCase("_sV").getCamelCase());
    assertEquals("_s_v", ParamName.bySnakeCase("_s_v").getSnakeCase());
  }

  @Test
  public void byUpperCamelCase() {
    assertEquals("up_ca_ca", ParamName.byUpperCamelCase("UpCaCa").getSnakeCase());
  }

  @Test
  public void compare() {
    assertTrue(ParamName.byCamelCase("ab").compareTo(ParamName.byCamelCase("cd")) < 0);
    assertEquals(0, ParamName.byCamelCase("ab").compareTo(ParamName.byCamelCase("ab")));

    assertTrue(ParamName.byCamelCase("name").compareTo(ParamName.byCamelCase("ab")) < 0);
    assertTrue(ParamName.byCamelCase("ab").compareTo(ParamName.byCamelCase("name")) > 0);
    assertEquals(0, ParamName.byCamelCase("name").compareTo(ParamName.byCamelCase("name")));
  }
}
