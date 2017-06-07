#!/bin/sh
exec < "$1" > "$2"
echo "package com.gen;"
echo "public class MergedLibraryMapping {"
sed 's/[.]/_/g;s/\(.*\) \(.*\)/public static final String \1 = "\2";/'
echo "}"
