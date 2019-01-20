All jars in this directory where processed with jarjar tool
java -jar jarjar.jar process jarjar-rules <lib>.jar <lib>.jar
to make them use shaded-guava-20.jar instead of default
guava library used by rest of Buck's codebase. This is because
Android is using obsolete APIs that have been removed long time
ago and it does not look as if Google has any plans to do something
about this - https://issuetracker.google.com/issues/64604704.
