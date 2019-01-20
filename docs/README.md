This directory contains the source files for Buck's HTML documentation,
as well as the scripts to publish that documentation.

Editing Documentation
=====================
Buck documentation is written using
[Closure Templates](https://developers.google.com/closure/templates/).
The documentation can be viewed locally by running the following
command:

    ./docs/soyweb-local.sh

and then navigating to <http://localhost:9811/> in your browser. 

The typical edit/refresh style of web development applies for editing
these docs: edit a documentation source file, then refresh the
corresponding page in your local view of the docs. This edit/refresh
workflow is made possible via [plovr](http://plovr.com/soyweb.html),
which is a build tool for Closure Templates.

Ideally, changes to Buck code will include updates to these docs in the
same commit so that the relationship between the code and documentation
changes is clear.


Publishing Documentation
========================

This documentation is hosted publicly at <http://facebook.github.com/buck/>
using [GitHub Pages](http://pages.github.com/).
Therefore, to publish this documentation, you must commit it on the
`gh-pages` branch of the GitHub repository by running:

    # Both build the docs with plovr in the background
    # (serving them on TCP port 9814) and push the
    # docs to GitHub Pages
    ./docs/publish.sh --start-soyweb

Because this script interacts with GitHub, you should have your
GitHub credentials configured as described at
[Generating a new SSH key and adding it to the ssh-agent](https://help.github.com/articles/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent/).


Creating a New Article
======================

Create a file and seed it with the following content:

    {namespace buck.ADD_YOUR_PAGE_NAME}

    /***/
    {template .soyweb}
      {call buck.page}
        {param title: 'ADD_YOUR_TITLE' /}
        {param content}

    ADD_YOUR_CONTENT_HERE

        {/param}
      {/call}
    {/template}

Update the three placeholders in all caps and you should be good to go!
