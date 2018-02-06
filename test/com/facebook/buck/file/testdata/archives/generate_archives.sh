#!/bin/sh
set -e
set -x
mkdir -p archive_temp archive_temp/root archive_temp/root_sibling archive_temp/root/src/com/facebook/buck archive_temp/root/alternative
echo "class Main { public static void main(String[] args) { return; } }" > archive_temp/root/src/com/facebook/buck/Main.java
echo "class Other { public static void main(String[] args) { return; } }" > archive_temp/root_sibling/Other.java
pushd archive_temp/root/alternative
ln -s ../src/com/facebook/buck/Main.java Link.java
ln -s Link.java Main.java
popd
echo "#!/bin/sh" > archive_temp/root/echo.sh
echo "echo 'testing'" >> archive_temp/root/echo.sh
chmod u+x archive_temp/root/echo.sh
mkdir archive_temp/root/empty_dir
pushd archive_temp
zip -y -r ../output.zip *
zip -r ../output_no_symlinks.zip * -i $(find . -not -type l -not -name '.' | sort | sed s,./,,)
for i in ".tar cvf" ".tar.bz2 cjvf" ".tar.gz czvf" ".tar.xz cJvf"; do
  extension=$(echo $i | awk {'print $1'})
  args=$(echo $i | awk {'print $2'})
  gtar $args ../output${extension} *
done
popd
rm -rf archive_temp
