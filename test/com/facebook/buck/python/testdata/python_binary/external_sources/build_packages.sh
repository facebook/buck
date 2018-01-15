#!/usr/bin/env bash
set -e

# Quick script to generate egg_package and wheel_package for integration tests

temp_dir=$(mktemp -d)
trap "rm -rf \"$temp_dir\"" EXIT
cp -prvf wheel_package/* "${temp_dir}/"
pushd "${temp_dir}"
python setup.py bdist_wheel
popd
cp -pvf "${temp_dir}/dist/"*.whl wheel_package-0.0.1-py2-none-any.whl
