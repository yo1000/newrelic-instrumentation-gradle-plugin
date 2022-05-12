# newrelic-instrumentation-gradle-plugin

Plugin that automatically creates XML file for NewRelic custom instrumentation for Gradle.

refs: https://docs.newrelic.com/docs/agents/java-agent/custom-instrumentation/java-instrumentation-xml

## Requirements
* JDK8+

## Usage
Configure plugins section in build.gradle file.

```groovy
plugins {
    id 'com.yo1000.newrelic-instrumentation' version '1.0.1'
}

newrelicInstrumentation {
    outputDirectory = buildDir
    namespaceUri = 'https://newrelic.com/docs/java/xsd/v1.0'
    name = 'newrelic-extension'
    version = '1.0'
    enabled = true
}
```

When Run the Gradle build,
NewRelic custom instrumentation XML will be created.

```
gradle newrelic-instrumentation

ls -l build/newrelic-instrumentation/extensions/
total 4
-rwxrwxrwx 1 ubuntu ubuntu 1931 Dec  4 16:13 newrelic-extension.xml

cat build/newrelic-instrumentation/extensions/newrelic-extension.xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<extension enabled="true" name="newrelic-extension" version="1.0">
    <instrumentation>
        <pointcut transactionStartPoint="true">
            <className>...
```

### Configuration parameters
Configurable parameters are follows.

#### outputDirectory
Compiled class files location.

#### namespaceUri
XML namespace.

#### name
Custom instrumentation XML file name.

#### version
Custom instrumentation XML version.
If any same named instrumentation XML, only enable to have the latest version.

#### enabled
Enable Custom instrumentation XML.

#### manuallyDefinitions
Custom instrumentation additional pointcut configs by manually.
Method definition separator is space characters. (e.g., ` `, `\t`, `\n`)
Inner class name separator is not `$`, use be `-`.

defaults: _empty_

examples:
```groovy
newrelicInstrumentation {
    outputDirectory = buildDir
    namespaceUri = 'https://newrelic.com/docs/java/xsd/v1.0'
    name = 'newrelic-extension'
    version = '1.0'
    enabled = true
    manuallyDefinitions.put('java.util.ArrayList', 'get add set')
    manuallyDefinitions.put('com.yo1000.demo.ExampleList', 'get add set')
    manuallyDefinitions.put('com.yo1000.demo.Foo-Bar', 'baz')
}
```

## How to Build
Install to Maven local repository.

```
./gradlew publishToMavenLocal
```
