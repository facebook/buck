# Confirm how line comments affect INDENT/DEDENT boundaries

# Doubly-nested if/then/else, where each
# inner suite should parse the same:
if level1 == "if":
  # start if
  if level2 == "if":
    # start
    foo(level1, level2) # inside if/if
    # end
  elif level2  == "elif":
    # start
    foo(level1, level2) # inside if/elif
    # end
  else:
    # start
    foo(level1, level2) # inside if/else
    # end
  # end
elif level == "elif":
  # start
  if level2 == "if":
    # start
    foo(level1, level2) # inside elif/if
    # end
  elif level2  == "elif":
    # start
    foo(level1, level2) # inside elif/elif
    # end
  else:
    # start
    foo(level1, level2) # inside elif/else
    # end
  # end
else:
  # start
  if level2 == "if":
    # start
    foo(level1, level2) # inside else/if
    # end
  elif level2  == "elif":
    # start
    foo(level1, level2) # inside else/elif
    # end
  else:
    # start
    foo(level1, level2) # inside else/else
    # end
  # end
# outside doubly-nested if/elif/else

if True:
  if True:
    if True:
      pass
      # Dangling INDENT in middle of file

if True:
  if True:
    if True:
      pass
      # Dangling INDENT at end of file