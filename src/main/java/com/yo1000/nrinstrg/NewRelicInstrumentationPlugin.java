package com.yo1000.nrinstrg;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.objectweb.asm.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NewRelicInstrumentationPlugin implements Plugin<Project> {
    public interface NewRelicInstrumentationPluginExtension {
        Property<File> getOutputDirectory();
        Property<String> getNamespaceUri(); // "https://newrelic.com/docs/java/xsd/v1.0";
        Property<String> getName(); // "newrelic-extension";
        Property<String> getVersion(); // "1.0";
        Property<Boolean> getEnabled(); // true;
        MapProperty<String, String> getManuallyDefinitions();
        Property<String> getAsm(); // "9";
    }

    private static final Logger logger = Logging.getLogger(NewRelicInstrumentationPlugin.class);

    private static final String ASM_DEFAULT = "9";

    private static final Map<String, Integer> APIS = Collections.unmodifiableMap(new HashMap<String, Integer>() {{
        put("5", Opcodes.ASM5);
        put("6", Opcodes.ASM6);
        put("7", Opcodes.ASM7);
        put("8", Opcodes.ASM8);
        put("9", Opcodes.ASM9);
    }});

    private static final int API_DEFAULT = APIS.get(ASM_DEFAULT);

    @Override
    public void apply(final Project project) {
        final NewRelicInstrumentationPluginExtension extension = project.getExtensions()
                .create("newrelicInstrumentation", NewRelicInstrumentationPluginExtension.class);

        project.getTasks().create("newrelic-instrumentation", task -> {
            task.doLast(t -> {
                execute(extension);
            });
        });
    }

    protected Map.Entry<ClassName, MethodNames> visitClassFile(int api, File f) throws IOException {
        final ClassMethodCapturingVisitor visitor = new ClassMethodCapturingVisitor(api);

        try (InputStream in = new FileInputStream(f)) {
            new ClassReader(in).accept(visitor, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            logger.error(f.getAbsolutePath(), e);
        }

        return new AbstractMap.SimpleEntry<>(visitor.getClassName(), visitor.getMethodNames());
    }

    protected ClassNameMappedMethodNames walkClassesDirectory(int api, File directory) throws IOException {
        final ClassNameMappedMethodNames map = new ClassNameMappedMethodNames();

        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                File f = file.toFile();

                if (!f.getName().toLowerCase().endsWith(".class"))
                    return FileVisitResult.CONTINUE;

                map.put(visitClassFile(api, f));

                return super.visitFile(file, attrs);
            }
        });

        return map;
    }

    public void execute(NewRelicInstrumentationPluginExtension extension) {
        final File outputDirectory = extension.getOutputDirectory().get();
        final Map<String, String> manuallyDefinitions = extension.getManuallyDefinitions().getOrElse(Collections.emptyMap());
        final String namespaceUri = extension.getNamespaceUri().getOrElse("https://newrelic.com/docs/java/xsd/v1.0");
        final String name = extension.getName().getOrElse("newrelic-extension");
        final String version = extension.getVersion().getOrElse("1.0");
        final Boolean enabled = extension.getEnabled().getOrElse(true);
        final String asm = extension.getAsm().getOrElse(ASM_DEFAULT);
        final int api = APIS.get(asm) != null ? APIS.get(asm) : API_DEFAULT;

        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) return;

        try {
            ClassNameMappedMethodNames map = walkClassesDirectory(api, outputDirectory);

            manuallyDefinitions.forEach((classNameString, methodNameAsSpaceSeparatedString) -> {
                ClassName className = new ClassName(classNameString.replace('-', '$'));

                if (!map.containsKey(className)) map.put(className, new MethodNames());
                MethodNames methodNames = map.get(className);

                for (String s : methodNameAsSpaceSeparatedString.split("\\s+")) {
                    methodNames.add(new MethodName(s));
                }
            });

            Document document = DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .getDOMImplementation()
                    .createDocument(namespaceUri, "extension", null);

            Element root = (Element) document.getFirstChild();
            root.setAttribute("name", name);
            root.setAttribute("version", version);
            root.setAttribute("enabled", String.valueOf(enabled));

            Element instrElement = (Element) root.appendChild(document.createElement("instrumentation"));

            map.forEach(entry -> {
                if (entry.getValue().isEmpty()) return;

                Element pointcutElement = (Element) instrElement.appendChild(document.createElement("pointcut"));
                pointcutElement.setAttribute("transactionStartPoint", "true");

                pointcutElement.appendChild(document.createElement("className"))
                        .appendChild(document.createTextNode(entry.getKey().getValue()));

                entry.getValue().forEach(methodName ->
                        pointcutElement.appendChild(document.createElement("method"))
                                .appendChild(document.createElement("name"))
                                .appendChild(document.createTextNode(methodName.getValue())));
            });

            Path writeDir = outputDirectory.toPath().resolve("./newrelic-instrumentation/extensions");
            Files.createDirectories(writeDir);

            try (FileOutputStream out = new FileOutputStream(writeDir.resolve(name + ".xml").toFile())) {
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

                transformer.transform(new DOMSource(document), new StreamResult(out));
            }
        } catch (Exception e) {
            throw new GradleException(e.getMessage(), e);
        }
    }

    public static class ClassName {
        private final String value;

        public ClassName(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClassName className = (ClassName) o;
            return Objects.equals(value, className.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }

    public static class MethodName {
        private final String value;

        public MethodName(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodName that = (MethodName) o;
            return Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }

    public static class MethodNames extends ArrayList<MethodName> {
        @Override
        public String toString() {
            return "[" + stream()
                    .map(MethodName::getValue)
                    .collect(Collectors.joining(", "))
                    + "]";
        }
    }

    public static class ClassNameMappedMethodNames implements Iterable<Map.Entry<ClassName, MethodNames>> {
        private final Map<ClassName, MethodNames> classMethodsMap = new LinkedHashMap<>();

        public boolean containsKey(ClassName className) {
            return classMethodsMap.containsKey(className);
        }

        public MethodNames get(ClassName className) {
            return classMethodsMap.get(className);
        }

        public MethodNames put(Map.Entry<ClassName, MethodNames> entry) {
            return classMethodsMap.put(entry.getKey(), entry.getValue());
        }

        public MethodNames put(ClassName className, MethodNames methodNames) {
            return classMethodsMap.put(className, methodNames);
        }

        @Override
        public Iterator<Map.Entry<ClassName, MethodNames>> iterator() {
            return classMethodsMap.entrySet().iterator();
        }

        @Override
        public void forEach(Consumer<? super Map.Entry<ClassName, MethodNames>> action) {
            classMethodsMap.entrySet().forEach(action);
        }

        @Override
        public Spliterator<Map.Entry<ClassName, MethodNames>> spliterator() {
            return classMethodsMap.entrySet().spliterator();
        }
    }

    public static class ClassMethodCapturingVisitor extends ClassVisitor {
        private ClassName className;
        private final MethodNames methodNames = new MethodNames();

        ClassMethodCapturingVisitor(int api) {
            super(api);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = new ClassName(name.replaceAll("/", "."));
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (!name.equals("<init>") && !name.equals("<clinit>")) methodNames.add(new MethodName(name));
            return super.visitMethod(access, name, desc, signature, exceptions);
        }

        @Override
        public void visitAttribute(Attribute attr) {
            super.visitAttribute(attr);
        }

        @Override
        public void visitSource(String source, String debug) {
            super.visitSource(source, debug);
        }

        public ClassName getClassName() {
            return className;
        }

        public MethodNames getMethodNames() {
            return methodNames;
        }
    }
}
