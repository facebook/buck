#!/usr/bin/env python
"""
This exports a bunch of images from the base-dependency-diagram.graffle file
using py-appscript:
http://appscript.sourceforge.net/py-appscript/doc/appscript-manual/index.html

This example from GitHub was particularly helpful as a template:
https://github.com/fikovnik/omnigraffle-export/blob/master/omnigraffle_export/omnigraffle.py

I tried doing this in AppleScript, but that failed miserably:
http://stackoverflow.com/questions/25876467/how-to-read-a-property-of-a-value-passed-by-reference
"""

import appscript
import os.path

"""
Each entry must have the following properties:
  - canvas name of canvas, one of: {'full build', 'circular deps', 'android_binary'}
  - layers list of indices of layers to include. 1-based, starting from bottom to top
           as displayed in the Canvases column in OmniGraffle.
  - out name of file where image should be exported.
"""
IMAGE_DATA = [
  # gradle
  {
    'canvas': 'gradle',
    'layers': [1,],
    'out': 'gradle-rearranged.png',
  },
  {
    'canvas': 'gradle',
    'layers': [1, 2,],
    'out': 'gradle-r-dot-java.png',
  },
  {
    'canvas': 'gradle',
    'layers': [1, 2, 3,],
    'out': 'gradle-android-library-1.png',
  },
  {
    'canvas': 'gradle',
    'layers': [1, 2, 3, 4,],
    'out': 'gradle-android-library-2.png',
  },
  {
    'canvas': 'gradle',
    'layers': [1, 2, 3, 4, 5,],
    'out': 'gradle-classes-dot-dex.png',
  },
  {
    'canvas': 'gradle',
    'layers': [1, 2, 3, 4, 5, 6,],
    'out': 'gradle-android-binary.png',
  },

  # full build
  {
    'canvas': 'full build',
    'layers': [1, 2, 3,],
    'out': 'boxes-and-arrows.png',
  },
  {
    'canvas': 'full build',
    'layers': [1, 2, 3, 4,],
    'out': 'gen-r-txt.png',
  },
  {
    'canvas': 'full build',
    'layers': [1, 2, 4, 5,],
    'out': 'gen-r-dot-java.png',
  },
  {
    'canvas': 'full build',
    'layers': [1, 2, 4, 5, 6,],
    'out': 'gen-jar-files.png',
  },
  {
    'canvas': 'full build',
    'layers': [1, 2, 4, 5, 6, 7,],
    'out': 'gen-dex-files.png',
  },

  # android_binary
  {
    'canvas': 'android_binary',
    'layers': [1,],
    'out': 'android-binary-rules-only.png',
  },
  {
    'canvas': 'android_binary',
    'layers': [1, 2,],
    'out': 'android-binary-gen-resources.png',
  },
  {
    'canvas': 'android_binary',
    'layers': [1, 2, 3,],
    'out': 'android-binary-intermediate-dex.png',
  },
  {
    'canvas': 'android_binary',
    'layers': [1, 2, 3, 4,],
    'out': 'android-binary-uber-classes-dex.png',
  },
  {
    'canvas': 'android_binary',
    'layers': [1, 2, 3, 4, 5,],
    'out': 'android-binary-done.png',
  },

  # build trace
  {
    'canvas': 'build trace',
    'layers': [2, 3,],
    'out': 'build-trace.png',
  },

  # abi
  {
    'canvas': 'abi',
    'layers': [1,],
    'out': 'abi-graph.png',
  },
  {
    'canvas': 'abi',
    'layers': [1, 2, 4,],
    'out': 'abi-change-method-body.png',
  },
  {
    'canvas': 'abi',
    'layers': [1, 2, 3, 4, 5,],
    'out': 'abi-change-method-signature.png',
  },
  {
    'canvas': 'abi',
    'layers': [1, 2, 3, 4,],
    'out': 'abi-add-unused-public-method.png',
  },
]


def export_images(app, doc, canvas_map, directory):
  for data in IMAGE_DATA:
    canvas = canvas_map[data['canvas']]
    out = os.path.join(directory, data['out'])

    # Enable the specified set of layers.
    num_layers = len(canvas.layers())
    enabled_layers = set([num_layers - layer for layer in data['layers']])
    app.windows.first().canvas.set(canvas)
    for index in range(num_layers):
      layer = canvas.layers()[index]
      app.set(layer.visible, to=index in enabled_layers)

    # Export the .png.
    doc.save(as_='PNG', in_=out)
    print out


def main():
  omniGraffle = appscript.app('OmniGraffle Professional 5')
  directory = os.path.dirname(os.path.realpath(__file__))
  graffle = os.path.join(directory, 'base-dependency-diagram.graffle')
  omniGraffle.open(graffle)
  omniGraffle.activate()
  doc = omniGraffle.windows.first().document()
  canvas_map = {}
  for canvas in doc.canvases():
    canvas_map[canvas.name()] = canvas
  export_images(omniGraffle, doc, canvas_map, os.path.join(directory, 'generated'))


if __name__ == '__main__':
  main()
