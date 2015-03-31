package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WindowsCreateProcessEscapeTest {
  @Test
  public void testCases() {
    // An array of of input strings and the expected output.
    String[][] tests = {
        {
            "C:\\Windows\\",
            "C:\\Windows\\",
        },

        {
            "",
            "\"\"",
        },
        {
            " ",
            "\" \"",
        },
        {
            "\t",
            "\"\t\"",
        },

        {
            "\\",
            "\\",
        },
        {
            "\\\\",
            "\\\\",
        },
        {
            " \\",
            "\" \\\\\"",
        },
        {
            "\t\\",
            "\"\t\\\\\"",
        },

        {
            "\\\"",
            "\"\\\\\\\"\"",
        },
        {
            "\\a\\\"",
            "\"\\a\\\\\\\"\"",
        },
        {
            "\\\"a\\\"",
            "\"\\\\\\\"a\\\\\\\"\"",
        },
        {
            "\\\"\\\"",
            "\"\\\\\\\"\\\\\\\"\"",
        },
    };

    for (String[] test : tests) {
      assertEquals(2, test.length);
      assertEquals(test[1], WindowsCreateProcessEscape.quote(test[0]));
    }
  }
}
