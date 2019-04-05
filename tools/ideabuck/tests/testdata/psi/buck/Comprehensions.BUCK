# List, tuple, and dict comprehensions

# Simple comprehensions
numbers = [1,2,3]
list_comprehension = [str(n) for n in numbers]
tuple_comprehension = (str(n) for n in numbers)
dict_comprehension = {str(n): n for n in numbers}

# Iteration over tuples
pairs = [(1, "one"), (2, "two")]
list_comprehension = [number for number, name in pairs]
tuple_comprehension = (name for number, name in pairs)
dict_comprehension = {name: number for number, name in pairs}

# Comprehensions with if
list_comprehension = [number for number, name in pairs if number < 2]
tuple_comprehension = (name for number, name in pairs if number < 2)
dict_comprehension = {name: number for number, name in pairs if number < 2}

# Comprehensions with multiple fors and ifs
words = ["one", "two", "three"]
multiple_for = [letter for word in words for letter in word]
multiple_if = [word for word in words if 't' in word if 'e' not in word]

# Nested comprehensions inside comprehensions
nested = [a for a in (b for b in [c for c in (1,2,3)] if b) if a]
