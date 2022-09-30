# autodoc-collect

Autodoc-collect is a support library designed specifically to be used by [autodoc](https://github.com/tomfaulhaber/autodoc). It exists mainly as a hack to get avoid getting bitten by Clojure's transitive compilation bug [CLJ-322](http://dev.clojure.org/jira/browse/CLJ-322). See [The Problem](#problem), below, for the details.

`collect-info-to-file` is a function that produces a readable file containing various documentation information about a set of namespaces available on the current classpath. It does this by loading the namespaces and examining the metadata of public vars.

There's no reason that another tool that finds this functionality useful couldn't use this library, but I haven't done much to make it externally consumable. If you're interested in using it, feel free. Let me know and I'll do a some more documentation on usage and the output format and such.

If you simply want a machine readable index of the documentation buried in your program, consider using autodoc itself which produces a readable index as a side effect and can be invoked directly from leiningen.

## Usage

The library is not really designed for easy consumption, but can be run from the command line in the normal way by using `clojure.main` with the `-e` option to invoke the `collect-info-to-file` function.

The arguments to `collect-info-to-file`, in order, are:

Argument                  | What it is
--------------------------|-----------
root                      | The home directory of the project 
source-path               | The path of the source file within the project
namespaces-to-document    | A list of namespace prefixes to document, separated by ":". 
load-except-list          | A list of regular exception to match for namespaces that should be excluded. 
trim-prefix               | A string prefix to trim off all the namespaces when generated the reference doc.
out-file                  | The name of the output file. This file will be overwritten with new data.
branch-name               | The name of the branch if any. This is only used in the construction of the output data. No source code management operations are performed by `collect-info-from-file`

For example, an invocation of this might look like:

<pre>
java -cp <i>&lt;classpath including autodoc-collect></i> clojure.main -e \
    "(use 'autodoc-collect.collect-info) \
     (collect-info-to-file \"/home/tom/src/clj/leipzig\" \"src\" \
       \"leipzig.canon:leipzig.chord:leipzig.example.row-row-row-your-boat:leipzig.live:leipzig.melody:leipzig.scale:leipzig.temperament\" \
       \"/example/\" \"nil\" \"/tmp/collect-8017534325756896641.clj\" \
       \"nil\")"
</pre>

## The Problem <a name="problem"></a>

The issue is that we need to run a process that loads code in its native Clojure version and scans the namespaces. The `collect-info` namespace has a collection of functions to do this without requiring any other parts of autodoc or libraries that might be version specific (with the exception of the `load-files` namespace, also included here). 

It's possible to set things up so that the autodoc jar will not AOT compile these (by making sure that there's no path from `-main` to these namespaces). 

However, other namespaces can still cause a problem.

This happens when you're trying to autodoc a program that uses a different Clojure version from autodoc itself and that program depends on a namespace that autodoc has AOT compiled (because it is transitively accesible from `-main`).

Thus splitting out autodoc-collect into its own project allows us to use just autodoc-collect as a library rather than calling the autodoc library recursively. Since autodoc-collect depends on nothing and does no AOT compilation, this creates a minimal, clean addition that can dump the documentation to a file for processing by the full autodoc system.

The particular example that made me realize I had to do this was in the Leipzig composition library. 

Leipzig is built with Clojure 1.4 (as of this writing) and Autodoc is built with Clojure 1.5.1, but they both use the clojure.data.json library (indirectly, in Leipzig's case). Since clojure.data.json is transitively referenced from autodoc's `-main` function, it is in the Autodoc jar as a class compiled with Clojure 1.5.1. No matter where you put the Autodoc jar on the classpath, Clojure will prefer the compiled .class file over the source .clj file.

## License

Copyright 2013 Tom Faulhaber


Distributed under the Eclipse Public License, the same as Clojure.
