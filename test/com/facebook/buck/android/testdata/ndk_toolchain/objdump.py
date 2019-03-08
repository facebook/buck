import sys

import impl

parser = impl.argparser()
(options, args) = parser.parse_known_args()

impl.log(options, args)

sys.exit(1)