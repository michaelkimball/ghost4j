## Default Behavior

Ghost4J uses the default JVM encoding, which is inherited from the host OS or specified by the `file.encoding` system property.

## Overriding the Encoding

In some cases it is useful to specify a different encoding for stdin interactions. Set the `ghost4j.encoding` system property:

```
-Dghost4j.encoding=UTF-8
```

This value is also propagated to forked JVMs when using multi-process mode (see [[Thread-Safety-and-Multi-Threading]]).

**Important:** if you change `ghost4j.encoding` at runtime, destroy the singleton first — otherwise the change will not take effect:

```java
Ghostscript.deleteInstance();
```
