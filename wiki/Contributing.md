Pull requests are welcome — bug fixes, new features, or any other contribution.

## Code Conventions

Ghost4J follows the [standard Java code conventions](http://www.oracle.com/technetwork/java/javase/documentation/codeconvtoc-136057.html) plus the rules below.

### File Headers

Every source file must include:

```java
/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
```

### Author

The initial author of each class must appear in the Javadoc comment with `@author`:

```java
@author Gilles Grousset (gi.grousset@gmail.com)
```

### Comments

- Javadoc comments are required on every method in every class.
- Inline comments should be used for complex or lengthy logic.

### Unit Tests

Every "testable" class must have a corresponding unit test. A class is testable if its methods contain logic that can produce different results.

At minimum, test the happy path. Error case tests are recommended but not mandatory.

Methods do **not** need tests when:
- There is no logic (e.g. getters/setters)
- The test cannot run as a black box (requires an external uncontrolled component)

## External Libraries

Ghost4J aims to be as lightweight as possible. New dependencies must be discussed before being added.
