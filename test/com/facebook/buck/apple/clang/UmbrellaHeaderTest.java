package com.facebook.buck.apple.clang;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class UmbrellaHeaderTest {

  @Test
  public void testUmbrellaHeader() {
    UmbrellaHeader header = new UmbrellaHeader(ImmutableList.of("header1.h", "header2.h"));
    assertEquals("#import \"header1.h\"\n" + "#import \"header2.h\"\n", header.render());
  }
}
