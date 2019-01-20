M_1 = [
  'm1.java',
]

M_2 = [
  'm2.java',
]

android_library(
  name = 'lib',
  # Expression
  srcs = glob(['**/*.java'], excludes = M_1 + M_2),
)

project_config(
  src_target = ':lib',
)
