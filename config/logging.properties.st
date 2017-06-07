# java.util.logging.LogManager configuration properties for Buck
#
# Also see logging.test.properties, which controls logging in unit tests.
#
# See http://docs.oracle.com/javase/7/docs/api/java/util/logging/LogManager.html
# for docs on the keys and values in this file.

# The log levels in this file come from java.util.logging.Level.
# Here's how they map to com.buck.log.Logger APIs:

# j.u.l   | buck
# ----------------
# SEVERE  | error()
# WARNING | warn()
# INFO    | info()
# FINE    | debug()
# FINER   | verbose()

# The special log level "ALL" means all log messages will
# be logged, and "OFF" means no log messages will be logged.

# Globally ignore and completely drop any log messages under this
# level (before passing them to the console / log files / etc.)
#
# If you want to enable more verbose logging, do it here.
.level=FINE

# Enable console logging handler and file handler to write rotating log files under
# buck-out/log/buck-*.log in the project(s) being used.
handlers=com.facebook.buck.cli.bootstrapper.ConsoleHandler,com.facebook.buck.cli.bootstrapper.LogFileHandler,com.facebook.buck.cli.bootstrapper.MemoryHandler,java.util.logging.FileHandler

# We handle console events via the event bus, so disable console logging by default.
com.facebook.buck.log.ConsoleHandler.level=SEVERE

# http client is way too chatty at FINER level.
httpclient.wire.content.level=SEVERE
httpclient.wire.header.level=SEVERE

# Log to buck-out/log/buck-*.log.
java.util.logging.FileHandler.pattern=<default_file_pattern>

# Write to disk all log messages not otherwise filtered by the top-level ".level" property.
java.util.logging.FileHandler.level=ALL

# Rotate up to this many log files, then start deleting the oldest one.
java.util.logging.FileHandler.count=<default_count>

# Replace the default fugly multiline log formatter with a custom one.
java.util.logging.FileHandler.formatter=com.facebook.buck.cli.bootstrapper.LogFormatter

# Ignore the environment and always write UTF-8 to files.
java.util.logging.FileHandler.encoding=UTF-8

# Use a circular buffer to store high granularity logging in memory, only interesting if we hit
# an error.
com.facebook.buck.log.memory.MemoryHandler.level=ALL

# Define the buffer size.
com.facebook.buck.log.memory.MemoryHandler.size=100

# Define the push level.
com.facebook.buck.log.memory.MemoryHandler.push=SEVERE

# Max size in bytes all logs will take.
com.facebook.buck.log.LogFileHandler.max_size_bytes=<default_max_size_bytes>

# Max number of logs preserved in disk.
com.facebook.buck.log.LogFileHandler.count=25

# Put any per-package/class logging configuration here.
#
# Note: Log levels are applied in the order they appear in this file,
# so settings for child packages and/or classes must come after
# parents. That means the root logger (".") must be at the top of this file.

# Example: Verbose logging for all classes under com.facebook.buck
# com.facebook.buck.level=FINE

# Example: Disable logging for all classes under com.facebook.buck.rules
# com.facebook.buck.rules.level=OFF

# Disable Jetty debug noise (logged at level FINE)
org.eclipse.jetty.level=INFO
oshi.level=OFF
