/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.i18n;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;
import java.util.function.Function;
import org.junit.Test;

public class NumberFormatterTest {
  private static Function<Locale, NumberFormat> decimalFormatCreator() {
    return locale -> {
      NumberFormat format = NumberFormat.getNumberInstance(locale);
      format.setMaximumFractionDigits(10);
      return format;
    };
  }

  @Test
  public void formatDoubleWithUSEnglishUsesDecimalPoint() {
    String formatted = new NumberFormatter(decimalFormatCreator()).format(Locale.US, 1.2345);
    assertThat(formatted, equalTo("1.2345"));
  }

  @Test
  public void formatDoubleWithGermanUsesDecimalComma() {
    String formatted = new NumberFormatter(decimalFormatCreator()).format(Locale.GERMAN, 1.2345);
    assertThat(formatted, equalTo("1,2345"));
  }

  @Test
  public void formatLongWithUSEnglishUsesThousandsComma() {
    String formatted = new NumberFormatter(decimalFormatCreator()).format(Locale.US, 123456789L);
    assertThat(formatted, equalTo("123,456,789"));
  }

  @Test
  public void formatLongWithGermanUsesThousandsPoint() {
    String formatted =
        new NumberFormatter(decimalFormatCreator()).format(Locale.GERMAN, 123456789L);
    assertThat(formatted, equalTo("123.456.789"));
  }

  @Test
  public void parseDoubleWithUSEnglishUsesDecimalPoint() throws ParseException {
    Object parsed = new NumberFormatter(decimalFormatCreator()).parse(Locale.US, "1.2345");
    assertThat(parsed, instanceOf(Double.class));
    assertThat((Double) parsed, closeTo(1.2345, 1e6));
  }

  @Test
  public void parseDoubleWithGermanUsesDecimalComma() throws ParseException {
    Object parsed = new NumberFormatter(decimalFormatCreator()).parse(Locale.GERMAN, "1,2345");
    assertThat(parsed, instanceOf(Double.class));
    assertThat((Double) parsed, closeTo(1.2345, 1e6));
  }

  @Test
  public void parseLongWithUSEnglishUsesThousandsComma() throws ParseException {
    Object parsed = new NumberFormatter(decimalFormatCreator()).parse(Locale.US, "123,456,789");
    assertThat(parsed, instanceOf(Long.class));
    assertThat(parsed, equalTo(123456789L));
  }

  @Test
  public void parseLongWithGermanUsesThousandsComma() throws ParseException {
    Object parsed = new NumberFormatter(decimalFormatCreator()).parse(Locale.GERMAN, "123.456.789");
    assertThat(parsed, instanceOf(Long.class));
    assertThat(parsed, equalTo(123456789L));
  }
}
