===================
PEP 484: Type Hints
===================

This GitHub repo is used for development of typing module defined by PEP 484.

Authors
-------

* Guido van Rossum

* Jukka Lehtosalo

* Łukasz Langa

BDFL-Delegate
-------------

The BDFL-Delegate is Mark Shannon.  This means he gets to be the final
reviewer of PEP 484 and ultimately gets to accept or reject it -- see
PEP 1 (https://www.python.org/dev/peps/pep-0001/).

Important dates
---------------

The dates for inclusion of typing.py in Python 3.5 are derived
from the Python 3.5 release schedule as documented in PEP 478
(https://www.python.org/dev/peps/pep-0478/):

* May 24, 2015: Python 3.5.0 beta 1 -- PEP 484 accepted, typing.py
  feature complete and checked into CPython repo

* August 9, 2015: Python 3.5.0 release candidate 1 -- Last chance for
  fixes to typing.py barring emergencies:

* September 13, 2015: Python 3.5.0 final release

Important URLs
--------------

The python.org rendering of PEP 484 lives at
https://www.python.org/dev/peps/pep-0484/.

Two related informational PEPs exist:

* An explanation of the theory behind type hints can be found in
  https://www.python.org/dev/peps/pep-0483/.

* A literature review is at https://www.python.org/dev/peps/pep-0482/.

The python.org site automatically updates (with a slight delay,
typically in the order of 5-60 minutes) whenever the python/peps repo is
updated.

Workflow
--------

* The typing.py module and its unittests are edited in the src
  subdirectory of this repo. The python2 subdirectory contains the Python2
  backport.

* The PEPs 484, 483, and 482 are edited in the GitHub python/peps repo.

* Use the GitHub issue tracker for this repo to collect concerns and
  TO DO items for PEPs 484, 483, and 482 as well as for typing.py.

* Accumulate changes in the GitHub repo, closing issues as they are
  either decided and described in PEP 484, or implemented in
  typing.py, or both, as befits the issue.  (Some issues will be
  closed as "won't fix" after a decision is reached not to take
  action.)

* Make frequent small commits with clear descriptions. Preferably use
  a separate commit for each functional change, so the edit history is
  clear, merge conflicts are unlikely, and it's easy to roll back a
  change when further discussion reverts an earlier tentative decision
  that was already written up and/or implemented.

* Push to GitHub frequently.

* Pull from GitHub frequently, rebasing conflicts carefully (or
  merging, if a conflicting change was already pushed).

* At reasonable checkpoints: post current versions of PEPs
  to python-dev, making sure to update the
  Post-History header in python/peps repo. This is typically done by Guido.

Tracker labels
--------------

* bug: Needs to be fixed in typing.py.

* to do: Editing task for PEP 484 or for this repo.

* enhancement: Proposed new feature.

* postponed: Idea up for discussion.

* out of scope: Somebody else's problem.

Workflow for mypy changes
-------------------------

* Use the GitHub issue tracker for the mypy repo (python/mypy). Jukka
  accepts GitHub Pull Requests at his discretion.

* At Jukka's discretion, he will from time to time copy typing.py and
  test_typing.py from the python/typing GitHub repo to the mypy repo.

* The full list of mypy issues marked as PEP 484 compatibility issues
  is here: https://github.com/python/mypy/labels/pep484

Workflow for CPython changes
----------------------------

* TBD: Workflow for copying typing.py and test_typing.py into the
  CPython repo.
