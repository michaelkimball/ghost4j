Pull requests are welcome — bug fixes, new features, or any other contribution.

## Build

The project uses **Gradle 9.5+** via the included wrapper. Always use `./gradlew` rather than a system-installed Gradle.

```bash
./gradlew compileJava          # compile main sources
./gradlew test                 # run all tests (requires Ghostscript installed)
./gradlew spotlessApply        # auto-format all Java sources
./gradlew jar -x test          # build JAR, skip tests
```

## Code Formatting

Formatting is enforced automatically by **Spotless** with **Google Java Format 1.27.0 AOSP style** (4-space indentation). Run `./gradlew spotlessApply` before committing. The check (`./gradlew spotlessCheck`) will fail if sources are not formatted.

Do not configure your IDE formatter manually — defer to `spotlessApply`.

## Code Conventions

### File Headers

Every source file must begin with:

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
@author Your Name (your@email.com)
```

### Comments

- Javadoc comments are required on every public method.
- Inline comments should be used for complex or non-obvious logic.

## Unit Tests

Tests use **JUnit 6 Jupiter**. Every testable class must have a corresponding test. A class is testable if its methods contain logic that can produce different results.

At minimum, test the happy path. Error case tests are recommended but not mandatory.

Methods do **not** need tests when:
- There is no logic (e.g. plain getters/setters)
- The test cannot run as a black box (requires an uncontrolled external component)

### Test Pattern

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

class MyComponentTest {

    @BeforeEach
    void setUp() throws Exception {
        // setup
    }

    @AfterEach
    void tearDown() throws Exception {
        Ghostscript.deleteInstance();
    }

    @Test
    void testSomeBehavior() throws Exception {
        // arrange, act, assert
    }
}
```

Tests live in `src/test/java/` and require Ghostscript to be installed at runtime (`libgs.so` on Linux, `gsdll64.dll` on Windows).

## External Libraries

Ghost4J aims to be as lightweight as possible. New dependencies must be discussed before being added.
