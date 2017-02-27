from __future__ import (absolute_import, division,
                        print_function, unicode_literals)

import json
import optparse
import os
from zipfile import ZipFile


def main():
    parser = optparse.OptionParser()
    parser.add_option('--config')
    parser.add_option('--keep')
    parser.add_option('--keyalias')
    parser.add_option('--keypass')
    parser.add_option('--keystore')
    parser.add_option('--out')
    parser.add_option('--proguard-config')
    parser.add_option('--proguard-map')
    parser.add_option('--sign', action='store_true')
    parser.add_option('-J')
    parser.add_option('-P')

    (options, args) = parser.parse_args()

    values = {
      # Environment variables.
      'ANDROID_SDK': os.environ['ANDROID_SDK'],
      'PWD': os.environ['PWD'],

      # Flag values.
      'config': options.config,
      'keep': options.keep,
      'keyalias': options.keyalias,
      'keypass': options.keypass,
      'keystore': options.keystore,
      'out': options.out,
      'proguard-config': options.proguard_config,
      'proguard-map': options.proguard_map,
      'sign': options.sign,
      'J': options.J,
      'P': options.P,
    }

    tmpout = options.out + '.tmp'
    with open(tmpout, 'w') as f:
        json.dump(values, f)

    with ZipFile(options.out, 'w') as zf:
        zf.write(tmpout, arcname='app_redex')


if __name__ == '__main__':
    main()
