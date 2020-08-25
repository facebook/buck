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

package com.facebook.buck.shell;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Helper to escape cmd.exe command lines.
 *
 * <p>Unfortunately, properly escaping a cmd.exe command line requires simultaneous consideration of
 * two different quoting mechanisms: 1) the naive cmd.exe quoting rules (which do not understand
 * nested or literal quotes) and 2) the generic Win32 command line argument parsing convention used
 * once cmd.exe passes final arguments to their destination. Additionally, some characters are
 * "special" to cmd.exe and may become metacharacters (characters that trigger cmd.exe-specific
 * command line transformations such as environment variable substitution, output redirection, etc.)
 * unless properly escaped, and in some cases also quoted. All of the above require additional
 * context that a simple command line string is unable to provide. Here are some helpful resources
 * that go into more detail:
 *
 * <p>
 *
 * <ul>
 *   <li><a
 *       href="https://docs.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-commandlinetoargvw">Microsoft
 *       documentation for Win32 function CommandLineToArgvW</a>
 *   <li><a
 *       href="https://docs.microsoft.com/en-us/cpp/cpp/main-function-command-line-args?view=vs-2019">main
 *       function and command-line arguments</a>
 *   <li><a
 *       href="https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/cmd">Microsoft's
 *       documentation for cmd.exe</a>
 *   <li><a
 *       href="https://docs.microsoft.com/en-us/archive/blogs/twistylittlepassagesallalike/everyone-quotes-command-line-arguments-the-wrong-way">Everyone
 *       quotes command line arguments the wrong way</a>
 *   <li><a href="http://www.daviddeley.com/autohotkey/parameters/parameters.htm#WIN">How Command
 *       Line Parameters Are Parsed</a>, Windows section
 * </ul>
 *
 * <p>This class provides an abstraction over a cmd.exe command line string that allows a user to
 * provide additional context: specifically, whether a substring within the larger command line
 * should be interpreted directly by cmd.exe (i.e. may contain metacharacters according to cmd.exe's
 * escaping and naive quoting rules) or not (i.e. any special characters should be definition be
 * escaped so that they do not inadvertently trigger cmd.exe command line transformations). Finally,
 * an {@link #escape(Command)} method is provided that will properly escape the final command line
 * string so that it can be passed to cmd.exe while preserving the original intent, with necessary
 * special characters properly quoted and/or escaped.
 */
public final class WindowsCmdExeEscaper {

  /**
   * Base class for representations of command substrings (i.e. a portion of a larger command line
   * string).
   *
   * @see EscapedCommandSubstring
   * @see UnescapedCommandSubstring
   */
  public static class CommandSubstring {

    public final String string;

    protected CommandSubstring(String string) {
      this.string = string;
    }
  }

  /**
   * Represents a command substring that is intended for direct consumption by cmd.exe, i.e. a
   * substring that may contain cmd.exe metacharacters. Metacharacters contained within this type of
   * substring will not be escaped when passing through {@link #escape(Command)}.
   *
   * <p>For example, the substring <code>"&gt; %OUT%"</code> contains two metacharacters: the
   * redirection metacharacter <code>'&gt;'</code> and variable substitution metacharacter <code>
   * '%' </code>.
   */
  public static final class EscapedCommandSubstring extends CommandSubstring {

    protected EscapedCommandSubstring(String string) {
      super(string);
    }
  }

  /**
   * Represents a command substring that is not intended for direct consumption by cmd.exe, i.e. a
   * substring that by definition does not contain cmd.exe metacharacters. Any special cmd.exe
   * characters that occur within this type of substring will be escaped such that cmd.exe will
   * never see them as metacharacters when passing through {@link #escape(Command)}.
   *
   * <p>For example, the substring <code>"&gt; %OUT%"</code> contains two special characters: <code>
   * '&gt;'</code> and <code>'%'</code>. To prevent these characters from being treated as the
   * redirection and variable substitution metacharacters, respectively, they would need to be
   * properly escaped.
   */
  public static final class UnescapedCommandSubstring extends CommandSubstring {

    protected UnescapedCommandSubstring(String string) {
      super(string);
    }
  }

  /**
   * Represents a cmd.exe command string to serve as input to {@link #escape(Command)}.
   *
   * <p>Use {@link Command.Builder} to build a <code>Command</code>.
   */
  public static final class Command {

    /** Helper to build a <code>Command</code>s. */
    public static final class Builder {
      private ImmutableList.Builder<CommandSubstring> substringsBuilder;

      public Builder() {
        substringsBuilder = ImmutableList.builder();
      }

      /**
       * Appends an "escaped" substring to the command. Any cmd.exe metacharacters in this substring
       * will be preserved un-escaped when passing through {@link #escape(Command)}.
       *
       * @param string Substring, possibly containing cmd.exe metacharacters, to append to the
       *     command.
       * @return Returns <code>this</code> to support chaining.
       */
      public Builder appendEscapedSubstring(String string) {
        if (!string.isEmpty()) {
          substringsBuilder.add(new EscapedCommandSubstring(string));
        }
        return this;
      }

      /**
       * Appends an "unescaped" substring to the command. Any cmd.exe special characters in this
       * substring will be escaped such that they will not treated as metacharacters when passing
       * through {@link #escape(Command)}.
       *
       * @param string Substring, possibly containing cmd.exe special characters which should be
       *     escaped to prevent them from being treated as cmd.exe metacharacters, to append to the
       *     command.
       * @return Returns <code>this</code> to support chaining.
       */
      public Builder appendUnescapedSubstring(String string) {
        if (!string.isEmpty()) {
          substringsBuilder.add(new UnescapedCommandSubstring(string));
        }
        return this;
      }

      /**
       * Returns a <code>Command</code> containing substrings previously appended using {@link
       * Builder#appendEscapedSubstring(String)} and {@link
       * Builder#appendUnescapedSubstring(String)}.
       *
       * @return Built <code>Command</code>.
       */
      public Command build() {
        return new Command(substringsBuilder.build());
      }
    }

    /**
     * Returns a new {@link Builder}.
     *
     * @return New {@link Builder}.
     */
    public static Builder builder() {
      return new Builder();
    }

    protected ImmutableList<CommandSubstring> substrings;

    private Command(ImmutableList<CommandSubstring> substrings) {
      this.substrings = substrings;
    }

    /**
     * Returns the list of substrings contained in this <code>Command</code>.
     *
     * @return List of substrings contained in this <code>Command</code>.
     */
    public ImmutableList<CommandSubstring> getSubstrings() {
      return substrings;
    }
  }

  /**
   * All cmd.exe special characters that will be treated as metacharacters unless quoted and/or
   * escaped. Comprehensive documentation on cmd.exe is limited, so this set is the union of those
   * found in two separate sources:
   *
   * <p>
   *
   * <ul>
   *   <li>https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/cmd
   *       (specifically the line saying "The following special characters require quotation
   *       marks...")
   *   <li>https://docs.microsoft.com/en-us/archive/blogs/twistylittlepassagesallalike/everyone-quotes-command-line-arguments-the-wrong-way
   *       (specifically the line saying "All of cmd's transformations are triggered by the presence
   *       of one of the metacharacters...")
   * </ul>
   */
  private static final String SPECIAL_CHARS = "!%&'()+,;<=>[]^`{|}~";

  @VisibleForTesting static final int META = (1 << 31); // cmd.exe metacharacter

  @VisibleForTesting
  static final int QUOTED = (1 << 30); // quoted according to CommandLineToArgvW rules

  private static boolean isSpecialChar(char c) {
    return (SPECIAL_CHARS.indexOf(c) != -1);
  }

  private static boolean isSpecialChar(int c) {
    return isSpecialChar((char) c);
  }

  private static boolean isMetaChar(int c) {
    return ((c & META) == META);
  }

  private static boolean isQuotedChar(int c) {
    return ((c & QUOTED) == QUOTED);
  }

  /**
   * Wrapper around <code>int[]</code> containing <code>char</code> from a command line along with
   * additional flags such as {@link #META} and {@link #QUOTED}.
   */
  @VisibleForTesting
  static final class AnnotatedString {
    public static final class Builder {
      private List<Integer> annotatedCharsBuilder;

      public Builder() {
        annotatedCharsBuilder = new ArrayList<Integer>();
      }

      boolean isEmpty() {
        return annotatedCharsBuilder.isEmpty();
      }

      void add(int c) {
        annotatedCharsBuilder.add(c);
      }

      void clear() {
        annotatedCharsBuilder.clear();
      }

      AnnotatedString build() {
        int[] annotatedChars = annotatedCharsBuilder.stream().mapToInt(i -> i).toArray();
        return new AnnotatedString(annotatedChars);
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    private final int[] annotatedChars;

    public AnnotatedString(final int[] annotatedChars) {
      this.annotatedChars = annotatedChars;
    }

    public AnnotatedString(String string) {
      annotatedChars = string.chars().toArray();
    }

    final int[] getAnnotatedChars() {
      return annotatedChars;
    }

    int length() {
      return annotatedChars.length;
    }

    boolean isEmpty() {
      return (annotatedChars.length == 0);
    }

    int getChar(int index) {
      return annotatedChars[index];
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder = new StringBuilder();
      Arrays.stream(annotatedChars).forEachOrdered(c -> stringBuilder.append((char) c));
      return stringBuilder.toString();
    }
  }

  /** Parses cmd.exe command strings for cmd.exe metacharacters. */
  @VisibleForTesting
  static final class CmdExeMetaParser {
    private int quoteCount;

    public CmdExeMetaParser() {
      this.quoteCount = 0;
    }

    /**
     * Parses <code>string</code> to identify cmd.exe metacharacters.
     *
     * <p>cmd.exe's quoting rules are orthogonal to generic Win32 command line argument quoting
     * rules. The biggest difference is that cmd.exe does not understand <code>'\\'</code> or how to
     * nest double quotes. As a result, determining whether cmd.exe sees a character as "in-quotes"
     * or not boils down to counting the number of unescaped <code>'"'</code> seen so far. If the
     * count is odd, "in-quotes" is active; if the count is even, "in-quotes" is not active.
     *
     * <p>With one exception, any cmd.exe special character which is not cmd.exe-quoted (as
     * described above) AND not escaped using the cmd.exe escape character <code>'^'</code> is a
     * metacharacter that can cause cmd.exe to trigger special transformations of the command line.
     *
     * <p>The exception is <code>'%'</code>, which still has special meaning to cmd.exe even when
     * cmd.exe-quoted (ex. both <code>echo %PATH%</code> and <code>echo "%PATH%"</code> result in
     * <code>%PATH%</code> being expanded). Outside of cmd.exe quotes, using <code>"^%"</code> will
     * prevent expansion while yielding a literal <code>'%'</code>.
     *
     * <p><code>parse</code> can be called repeatedly, i.e. <code>string</code> need not be a
     * complete command line. The cmd.exe "in-quotes" state is maintained across calls in order to
     * facilitate the parsing of multiple {@link EscapedCommandSubstring}s (that may be interleaved
     * with {@link UnescapedCommandSubstring}s).
     *
     * <p>The parsing process removes all usages of the cmd.exe escape character <code>'^'</code>
     * and instead indicates which special characters are metacharacters explicitly as a
     * per-character flag in the output. This is sufficient to later reproduce an escaped version of
     * <code>string</code> that maintains original intent later on.
     *
     * @param string cmd.exe string, which may contain metacharacters, to parse.
     * @return An {@link AnnotatedString} where each element is a <code>char</code> potentially ORed
     *     with the {@link #META} flag in the upper 16 bits to indicate that it has been identified
     *     as a cmd.exe metacharacter.
     */
    public AnnotatedString parse(String string) {
      AnnotatedString.Builder annotatedStringBuilder = AnnotatedString.builder();

      StringCharacterIterator charIter = new StringCharacterIterator(string);
      while (true) {
        if (charIter.current() == StringCharacterIterator.DONE) {
          break;
        }
        char c = charIter.current();
        charIter.next();
        // are we escaping with the cmd.exe escape character '^'?
        if (c == '^') {
          // is cmd.exe "in-quotes" mode off?
          if ((quoteCount & 1) == 0) {
            // are we at the end of the string?
            if (charIter.current() == StringCharacterIterator.DONE) {
              // yes; nothing to do but ignore it
              break;
            }
            // the character following '^' is a literal (i.e. if a special
            // character, then not interpreted as a metacharacter)
            annotatedStringBuilder.add(charIter.current());
            charIter.next();
          } else {
            // when cmd.exe "in-quotes" mode is on, '^' becomes a literal
            // rather than the escape character
            annotatedStringBuilder.add(c);
          }
        } else if (c == '"') {
          // begin or end a quoted section, i.e. toggle "in-quotes" mode on or
          // off
          ++quoteCount;
          annotatedStringBuilder.add(c); // i.e. '"'
        } else if (isSpecialChar(c)) {
          if (c == '%') {
            // '%' is always a metacharacter, whether cmd.exe "in-quotes" mode
            // is on or off
            annotatedStringBuilder.add(c | META);
          } else {
            // other special characters become metacharacters if cmd.exe
            // "in-quotes" mode is off
            annotatedStringBuilder.add(c | (((quoteCount & 1) == 0) ? META : 0));
          }
        } else {
          // not a special character, therefore treated as a literal whether
          // cmd.exe "in-quotes" mode is active or not
          annotatedStringBuilder.add(c);
        }
      }

      return annotatedStringBuilder.build();
    }
  }

  /**
   * Stateful parser that implements the same logic found in Win32 function <code>
   * CommandLineToArgvW()</code>, used to identify generic command line arguments.
   */
  @VisibleForTesting
  static final class CommandLineToArgvWParser {

    private enum State {
      SEPARATOR, // parsing whitespace separation between arguments
      ARGUMENT // parsing an argument
    }

    private State state;
    private StringBuilder separatorBuilder; // separating whitespace preceding current argument
    private AnnotatedString.Builder argBuilder; // current argument
    private boolean argPending; // there is an argument pending consumption
    private boolean inQuotes; // whether "in-quotes" mode active

    public CommandLineToArgvWParser() {
      this.state = State.SEPARATOR;
      this.separatorBuilder = new StringBuilder();
      this.argBuilder = AnnotatedString.builder();
      this.argPending = false;
      this.inQuotes = false;
    }

    /**
     * Parses {@link AnnotatedString}s, often produced by {@link CmdExeMetaParser}, for generic
     * command line arguments according to Win32 <code>CommandLineToArgvW()</code> rules. Any flags
     * on the input characters (such as {@link #META} as produced by {@link CmdExeMetaParser} or
     * {@link #QUOTED} added to special characters in unescaped substrings) are preserved in the
     * parsed arguments. The {@link #QUOTED} flag is added for any additional characters that were
     * quoted according to <code> CommandLineToArgvw()</code> rules during parsing.
     *
     * <p>A call to <code>parse</code> may produce zero or more arguments, along with preceding
     * separating whitespace as appropriate. When a complete argument has been seen during parsing,
     * <code>argConsumer</code> is invoked once per argument with both the preceding whitespace and
     * the parsed argument as an {@link AnnotatedString}.
     *
     * <p><code>parse</code> can be called repeatedly, i.e. <code>string</code> need not be a
     * complete command line. The parser remembers if it was looking at separating whitespace or an
     * argument, and whether <code>CommandLineToArgvW()</code> "in-quotes" mode was active, between
     * calls in order to to facilitate the parsing of multiple interleaved {@link
     * EscapedCommandSubstring}s and {@link UnescapedCommandSubstring}s.
     *
     * <p>NOTE: backslash escaping is NOT maintained across calls to <code>parse</code>, to lead to
     * more predictable outcomes when parsing multiple substrings.
     *
     * @param string An {@link AnnotatedString} where each element is a <code>char</code>
     *     potentially ORed with the {@link #META} flag in the upper 16 bits to indicate that the
     *     character is a cmd.exe metacharacter.
     * @param argConsumer A {@link BiConsumer} that will be invoked when an argument has been seen,
     *     with its preceding separating whitespace. The parsed argument is passed to the consumer
     *     as an optional {@link AnnotatedString} where each element is a <code>char</code>
     *     potentially ORed with the {@link #META} flag to indicate that the character is intended
     *     as a cmd.exe metacharacter and/or the {@link #QUOTED} flag to indicate that the character
     *     was originally quoted according to <code>CommandLineToArgvW()</code> rules.
     */
    public void parse(
        AnnotatedString string, BiConsumer<String, Optional<AnnotatedString>> argConsumer) {
      int index = 0;

      while (true) {
        if (state == State.SEPARATOR) {

          // consume whitespace
          while (index < string.length() && Character.isWhitespace((char) string.getChar(index))) {
            separatorBuilder.append((char) string.getChar(index));
            ++index;
          }

          if (index == string.length()) {
            break;
          } else {
            state = State.ARGUMENT;
          }
        } else if (state == State.ARGUMENT) {

          // Consume argument. Reaching this state guarantees that there will
          // be an argument produced for consumption. It may be empty, however,
          // so we track the fact that an argument is pending explicitly.
          argPending = true;

          int backslashCount = 0;
          while (index < string.length() && (char) string.getChar(index) == '\\') {
            ++backslashCount;
            ++index;
          }

          if (index == string.length()) {
            // n backslashes at the end of the string just become n backslashes
            for (int i = 0; i < backslashCount; ++i) {
              appendToArg('\\');
            }
            break;
          } else if ((char) string.getChar(index) == '"') {
            // n backslashes followed by a '"' become n/2 backslashes
            for (int i = 0; i < backslashCount / 2; ++i) {
              appendToArg('\\');
            }
            if ((backslashCount & 1) == 1) {
              // an odd number of backslashes produces a literal '"' and does
              // not change the "in-quotes" mode
              appendToArg('"');
            } else {
              // an even number of backslashes does not produce a literal '"'
              // but instead toggles "in-quotes" mode
              inQuotes = !inQuotes;
            }
            ++index;
          } else {
            // n backslashes not followed by a '"' become n backslashes
            for (int i = 0; i < backslashCount; ++i) {
              appendToArg('\\');
            }
            // handling of whitespace depends on whether "in-quotes" mode is
            // activated
            if (Character.isWhitespace((char) string.getChar(index))) {
              if (inQuotes) {
                // if in quotes, whitespace is part of the argument
                appendToArg(string.getChar(index));
                ++index;
              } else {
                // if not in quotes, whitespace terminates an argument
                consumeArg(argConsumer);
                state = State.SEPARATOR;
              }
            } else {
              // non-whitespace is always part of an argument
              appendToArg(string.getChar(index));
              ++index;
            }
          }
        }
      }
    }

    /**
     * Returns <code>true</code> if "in-quotes" mode is active according to <code>
     * CommandLineToArgvW()</code> rules.
     *
     * @return Whether "in-quotes" mode is active.
     */
    public boolean isInQuotes() {
      return inQuotes;
    }

    /**
     * Appends <code>string</code> verbatim to the current argument being parsed.
     *
     * @param string {@link AnnotatedString} to append to the current argument.
     */
    public void appendToArg(String string) {
      string.chars().forEachOrdered(c -> appendToArg(c));
    }

    /**
     * Consumes the current argument. Typically not called directly until a full command line has
     * been passed to <code>parse</code> via repeated invocation, and then once to ensure that the
     * consumer sees the final argument.
     *
     * @param argConsumer A {@link BiConsumer} that will be invoked when an argument has been seen,
     *     with its preceding separating whitespace. The parsed argument is passed to the consumer
     *     as an optional {@link AnnotatedString} where each element is a <code>char</code>
     *     potentially ORed with the {@link #META} flag to indicate that the character is a cmd.exe
     *     metacharacter and the {@link #QUOTED} flag to indicate that the character was originally
     *     quoted according to <code>CommandLineToArgvW()</code> rules.
     */
    public void consumeArg(BiConsumer<String, Optional<AnnotatedString>> argConsumer) {
      if (separatorBuilder.length() > 0 || argPending) {
        argConsumer.accept(
            separatorBuilder.toString(),
            argPending ? Optional.of(argBuilder.build()) : Optional.empty());
        separatorBuilder.setLength(0);
        argBuilder.clear();
        argPending = false;
      }
    }

    private void appendToArg(int c) {
      argBuilder.add(c | (inQuotes ? QUOTED : 0));
    }

    private void appendToArg(char c) {
      appendToArg((int) c);
    }
  }

  /**
   * Returns a version of {@link Command} that properly quotes and escapes any special characters in
   * {@link UnescapedCommandSubstring}s such that they will not be considered metacharacters by
   * cmd.exe.
   *
   * <p>NOTE: The output does not necessarily preserve the original contents of {@link
   * EscapedCommandSubstring}s verbatim, but should preseve original intent of those substrings.
   *
   * @param command {@link Command} to escape.
   * @return Escaped version of <code>command</code> as a {@link String}.
   */
  public static String escape(Command command) {
    StringBuilder escapedBuilder = new StringBuilder();

    CmdExeMetaParser cmdExeMetaParser = new CmdExeMetaParser();

    BiConsumer<String, Optional<AnnotatedString>> argConsumer =
        (separator, optionalArg) -> escapeArg(separator, optionalArg, escapedBuilder);
    CommandLineToArgvWParser commandLineToArgvWParser = new CommandLineToArgvWParser();

    UnmodifiableIterator<CommandSubstring> substringIter = command.substrings.iterator();
    while (substringIter.hasNext()) {
      CommandSubstring substring = substringIter.next();

      if (substring instanceof EscapedCommandSubstring) {
        // parse for metacharacters and then parse for generic arguments
        commandLineToArgvWParser.parse(cmdExeMetaParser.parse(substring.string), argConsumer);
      } else if (substring instanceof UnescapedCommandSubstring) {
        // does this unescaped substring appear within a quoted argument?
        if (commandLineToArgvWParser.isInQuotes()) {
          // yes: append directly to in-progress, quoted argument
          commandLineToArgvWParser.appendToArg(substring.string);
        } else {
          // no: quote all special characters so that they will not be
          // considered metacharacters, and then parse for additional arguments
          AnnotatedString substringWithQuotedSpecialChars =
              new AnnotatedString(
                  substring.string.chars().map(c -> c | (isSpecialChar(c) ? QUOTED : 0)).toArray());
          commandLineToArgvWParser.parse(substringWithQuotedSpecialChars, argConsumer);
        }
      } else {
        throw new IllegalArgumentException("unknown CommandSubstring");
      }
    }

    // consume final argument, if any
    commandLineToArgvWParser.consumeArg(argConsumer);

    return escapedBuilder.toString();
  }

  /**
   * Writes an escaped argument (<code>arg</code>), along with any preceding whitespace (<code>
   *  separator</code>), to the final escaped command (<code>escapedBuilder</code>).
   *
   * @param separator Whitespace that precedes <code>arg</code> (i.e. separator between prior
   *     argument)
   * @param optionalArg {@link AnnotatedString} containing containing the characters of the argument
   *     to escape along with additional metadata flags ({@link #QUOTED} and {@link #META}).
   * @param escapedBuilder {@link StringBuilder} containing the escaped command line.
   */
  private static void escapeArg(
      String separator, Optional<AnnotatedString> optionalArg, StringBuilder escapedBuilder) {
    // append preceding separating whitespace, if any
    escapedBuilder.append(separator);

    if (optionalArg.isPresent()) {
      AnnotatedString arg = optionalArg.get();

      if (arg.isEmpty()) {
        // empty argument needs to be preserved
        escapedBuilder.append("^\"^\"");
      } else {
        // Treat the argument as a series of alternating runs of characters that
        // require quoting (i.e. have the QUOTE flag set) and characters that do
        // not require quoting (i.e. do not have the QUOTE flag set), where either
        // run is potentially of length zero. This method, as opposed to quoting
        // the entire argument wholesale, allows the command to most closely
        // resemble the original input. This is acceptable because the
        // CommandLineToArgvW convention explicitly allows quoting to be started
        // and stopped multiple times within the same argument.
        //
        // There are nuances here. For example, we have to handle cases such as
        // backslashes in a run that does not require quoting followed immediately
        // by a run requiring quoting, which changes how the backslash and double
        // quote escaping logic needs to work to make it comply with
        // CommandLineToArgvW.

        int index = 0;
        while (index < arg.length()) {
          index = escapeArgQuotedRun(arg, index, escapedBuilder);
          index = escapeArgUnquotedRun(arg, index, escapedBuilder);
        }
      }
    }
  }

  private static int escapeArgQuotedRun(
      AnnotatedString arg, int start, StringBuilder escapedBuilder) {
    int finish = start;

    while (finish < arg.length() && isQuotedChar(arg.getChar(finish))) {
      ++finish;
    }

    if (finish == start) {
      return start;
    }

    escapedBuilder.append("^\"");

    while (true) {
      int backslashCount = 0;
      while (start < finish && (char) arg.getChar(start) == '\\') {
        ++backslashCount;
        ++start;
      }

      if (start == finish) {
        // we've reached the end of the run and will be introducing
        // closing double quotes, so backslashes need to be escaped
        for (int i = 0; i < backslashCount * 2; ++i) {
          escapedBuilder.append('\\');
        }
        break;
      } else {
        if ((char) arg.getChar(start) == '"') {
          // escape backslashes and literal '"'
          for (int i = 0; i < backslashCount * 2 + 1; ++i) {
            escapedBuilder.append('\\');
          }
          escapedBuilder.append("^\"");
        } else {
          // not at the end of the run and no double quote,
          // so no need to escape backslashes
          for (int i = 0; i < backslashCount; ++i) {
            escapedBuilder.append('\\');
          }
          if (isSpecialChar(arg.getChar(start)) && !isMetaChar(arg.getChar(start))) {
            // a special character that is not a metacharacter needs to be escaped
            escapedBuilder.append('^');
          }
          escapedBuilder.append((char) arg.getChar(start));
        }
        ++start;
      }
    }

    escapedBuilder.append("^\"");

    return finish;
  }

  private static int escapeArgUnquotedRun(
      AnnotatedString arg, int start, StringBuilder escapedBuilder) {
    int finish = start;

    while (finish < arg.length() && !isQuotedChar(arg.getChar(finish))) {
      ++finish;
    }

    while (true) {
      int backslashCount = 0;
      while (start < finish && (char) arg.getChar(start) == '\\') {
        ++backslashCount;
        ++start;
      }

      if (start == finish) {
        // we've reached the end of the run...
        if (finish < arg.length()) {
          // ...but next up is a quoted run. Escape backslashes
          // to account for the next run's opening double quotes.
          for (int i = 0; i < backslashCount * 2; ++i) {
            escapedBuilder.append('\\');
          }
        } else {
          // this was the last run and no following double-quote,
          // so no need to escape backslashes
          for (int i = 0; i < backslashCount; ++i) {
            escapedBuilder.append('\\');
          }
        }
        break;
      } else {
        if ((char) arg.getChar(start) == '"') {
          // escape backslashes and literal '"'
          for (int i = 0; i < backslashCount * 2 + 1; ++i) {
            escapedBuilder.append('\\');
          }
          escapedBuilder.append("^\"");
        } else {
          // not at the end of the run and no double quote,
          // so no need to escape backslashes
          for (int i = 0; i < backslashCount; ++i) {
            escapedBuilder.append('\\');
          }
          if (isSpecialChar(arg.getChar(start)) && !isMetaChar(arg.getChar(start))) {
            // a special character that is not a metacharacter needs to be escaped
            escapedBuilder.append('^');
          }
          escapedBuilder.append((char) arg.getChar(start));
        }
        ++start;
      }
    }

    return finish;
  }
}
