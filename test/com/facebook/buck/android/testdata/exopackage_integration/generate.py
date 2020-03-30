#!/usr/bin/python

import json
import os
import shutil
import zipfile


config = json.loads(open("state.config").read())
package = config["package"]
data = config["data"]


def generate_file(template_path, path, data):
    with open(template_path, "r") as template_file:
        template = template_file.read()
        with open(path, "w") as outfile:
            outfile.write(template.format(**data))


longstring = "a bunch of useless data" * 100
print(len(longstring))

generate_file(
    "Java.template",
    "Java1.java",
    {"class": "Java1", "data": data["java1"], "longstring": longstring},
)
generate_file(
    "Java.template",
    "Java2.java",
    {"class": "Java2", "data": data["java2"], "longstring": longstring},
)
generate_file(
    "Java.template",
    "Java3.java",
    {"class": "Java3", "data": data["java3"], "longstring": longstring},
)

generate_file(
    "Java.template",
    "JavaModule1.java",
    {"class": "JavaModule1", "data": data["java_module1"], "longstring": longstring},
)
generate_file(
    "Java.template",
    "JavaModule2.java",
    {"class": "JavaModule2", "data": data["java_module2"], "longstring": longstring},
)
generate_file(
    "Java.template",
    "JavaModule3.java",
    {"class": "JavaModule3", "data": data["java_module3"], "longstring": longstring},
)

generate_file("MainJava.template", "MainJava.java", {"data": data["main_apk_java"]})

generate_file("Cxx.template", "cxx1.c", {"data": data["cxx1"]})
generate_file("Cxx.template", "cxx2.c", {"data": data["cxx2"]})
generate_file("Cxx.template", "cxx3.c", {"data": data["cxx3"]})

generate_file(
    "AndroidManifest.template",
    "AndroidManifest.xml",
    {"data": data["manifest"], "package": package},
)

resources_dir = "res/values"
if not os.path.exists(resources_dir):
    os.makedirs(resources_dir)
generate_file(
    "Resources.template", "res/values/strings.xml", {"resources": data["resources"]}
)

main_resources_dir = "main_res/values"
if not os.path.exists(main_resources_dir):
    os.makedirs(main_resources_dir)
generate_file(
    "MainResources.template",
    "main_res/values/strings.xml",
    {"main_apk_resources": data["main_apk_resources"]},
)

assets_dir = "assets"
if not os.path.exists(assets_dir):
    os.mkdir(assets_dir)
with open(os.path.join(assets_dir, "asset.txt"), "w") as assetfile:
    assetfile.write(data["assets"])

with zipfile.ZipFile("java_resources.jar", "w") as zipout:
    zipout.writestr("java_resources.txt", data["java_resources"])

shutil.copy("state.config", "config.processed")
