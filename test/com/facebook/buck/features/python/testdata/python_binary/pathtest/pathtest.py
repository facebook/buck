# Fails if we have "" on sys.path
from contextlib import contextmanager

print("Imported contextmanager!: %r" % contextmanager)
