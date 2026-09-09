package org.acme.mcp.runtime;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 JDK {@link JavaCompiler} 的实时 Java 编译执行器.
 *
 * <p>用户代码就是真实 Java, 支持完整语法 (lambda/try-with-resources/泛型/注解等).
 * 外部 JAR 走 {@link URLClassLoader}, 无沙箱白名单限制.
 * 每次执行独立类加载器, finally 关闭, 作用域严格在当前调用线程.
 * 按 code+jarPaths 哈希缓存已编译字节码, 减少重复编译开销.
 *
 * <p>使用:
 * <pre>{@code
 * Object r = JdkRuntime.evalJavaCode(code, params);
 * Object r = JdkRuntime.evalJavaCode(code, params, List.of("path/to/lib.jar"));
 * }</pre>
 */
public final class JdkRuntime {

    private static final AtomicLong CLASS_COUNTER = new AtomicLong(0);
    private static final Map<String, CompiledClass> CACHE = new ConcurrentHashMap<>();

    private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;\\s*$");
    private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;\\s*$");
    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)\\b");

    private JdkRuntime() {}

    /**
     * 编译并执行 Java 代码.
     *
     * @param javaCode 用户代码 (完整类或方法体)
     * @param params   上下文参数; 包装为 public static Object execute(Map) 形参
     * @param jarPaths 外部 JAR 路径列表 (可为 null/空)
     * @return 写入 params 的副作用 + execute 方法返回值
     */
    public static Object evalJavaCode(String javaCode, Map<String, Object> params, Collection<String> jarPaths) {
        if (javaCode == null || javaCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Code cannot be empty");
        }
        if (params == null) {
            params = new HashMap<>();
        }
        List<String> jars = (jarPaths == null) ? Collections.emptyList() : new ArrayList<>(jarPaths);
        for (String jar : jars) {
            if (jar == null || jar.trim().isEmpty()) {
                throw new IllegalArgumentException("JAR 路径为空");
            }
            File f = new File(jar);
            if (!f.isFile()) {
                throw new IllegalArgumentException("外部 JAR 不存在: " + jar);
            }
        }

        String pkg = extractPackage(javaCode);
        String existingClassName = extractExistingClassName(javaCode);
        String className;
        String fullClassName;
        String source;

        if (existingClassName != null) {
            className = existingClassName;
            fullClassName = pkg.isEmpty() ? className : pkg + "." + className;
            source = javaCode;
        } else {
            className = "DynamicCode_" + CLASS_COUNTER.incrementAndGet();
            fullClassName = pkg.isEmpty() ? className : pkg + "." + className;
            source = wrapAsClass(javaCode, className, pkg);
        }

        String cacheKey = cacheKey(fullClassName, jars, source);
        CompiledClass compiled = CACHE.get(cacheKey);
        if (compiled == null) {
            compiled = compile(fullClassName, source, jars);
            CACHE.put(cacheKey, compiled);
        }

        ClassLoader externalLoader = compiled.newClassLoader(jars);
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        current.setContextClassLoader(externalLoader);
        try {
            Class<?> clazz = externalLoader.loadClass(fullClassName);
            Method exec = findExecuteMethod(clazz);
            if (exec == null) {
                throw new IllegalStateException("代码中未找到 execute(Map) 或 main(String[]) 方法");
            }
            exec.setAccessible(true);
            if (exec.getParameterCount() == 1 && exec.getParameterTypes()[0] == Map.class) {
                return exec.invoke(null, params);
            } else {
                return exec.invoke(null, (Object) new String[0]);
            }
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("执行失败: " + cause.getMessage(), cause);
        } finally {
            current.setContextClassLoader(original);
            try {
                ((URLClassLoader) externalLoader).close();
            } catch (IOException ignored) {}
        }
    }

    /** 无外部 JAR 便捷重载 */
    public static Object evalJavaCode(String javaCode, Map<String, Object> params) {
        return evalJavaCode(javaCode, params, null);
    }

    private static Method findExecuteMethod(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethod("execute", Map.class);
        } catch (NoSuchMethodException ignored) {}
        try {
            return clazz.getDeclaredMethod("main", String[].class);
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    /**
     * 把方法体/语句包装为完整 Java 源.
     * 用户写的 import / package 行会被提升到类外, 避免 "illegal start of expression" 编译错误.
     */
    private static String wrapAsClass(String body, String className, String pkg) {
        StringBuilder imports = new StringBuilder();
        StringBuilder bodyCode = new StringBuilder();

        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (PACKAGE_LINE.matcher(trimmed).matches()) {
                continue;
            }
            if (IMPORT_LINE.matcher(trimmed).matches()) {
                imports.append(line).append('\n');
                continue;
            }
            bodyCode.append("        ").append(line).append('\n');
        }

        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n");
        }
        sb.append(imports);
        // 显式添加反射 import: 用户用 Class.forName + getMethod + invoke 时不用自己加
        if (imports.indexOf("import java.lang.reflect.Method") < 0) {
            sb.append("import java.lang.reflect.Method;\n");
        }
        sb.append("public class ").append(className).append(" {\n");
        sb.append("    public static Object execute(java.util.Map<String, Object> params) throws Exception {\n");
        sb.append(bodyCode);
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String extractPackage(String code) {
        for (String line : code.split("\n")) {
            Matcher m = PACKAGE_LINE.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "";
    }

    private static String extractExistingClassName(String code) {
        for (String line : code.split("\n")) {
            Matcher cm = CLASS_DECL.matcher(line);
            if (cm.find()) {
                return cm.group(1);
            }
        }
        return null;
    }

    private static String cacheKey(String fullClassName, List<String> jars, String source) {
        StringBuilder k = new StringBuilder(fullClassName).append('|');
        for (String j : jars) k.append(j).append(';');
        k.append('|').append(source.hashCode());
        return k.toString();
    }

    private static CompiledClass compile(String fullClassName, String source, List<String> jars) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available. Please run with JDK (not JRE)");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        InMemoryFileManager fileManager = null;
        StandardJavaFileManager stdFm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            fileManager = new InMemoryFileManager(stdFm);

            SimpleJavaFileObject src = new SimpleJavaFileObject(
                    URI.create("string:///" + fullClassName.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };

            List<String> options = new ArrayList<>();
            options.add("-source");
            options.add("17");
            options.add("-target");
            options.add("17");
            String existingCp = System.getProperty("java.class.path", "");
            StringBuilder cp = new StringBuilder(existingCp);
            for (String jar : jars) {
                if (cp.length() > 0) cp.append(File.pathSeparator);
                cp.append(new File(jar).getAbsolutePath());
            }
            options.add("-classpath");
            options.add(cp.toString());

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, options, null, Collections.singletonList(src));

            boolean ok = task.call();
            if (!ok) {
                StringBuilder errors = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    errors.append(d.getKind()).append(": ")
                            .append(d.getSource() != null ? d.getSource().getName() : "?")
                            .append(" line ").append(d.getLineNumber())
                            .append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
                }
                throw new RuntimeException(errors.toString());
            }
            return new CompiledClass(fileManager.snapshot());
        } finally {
            try { stdFm.close(); } catch (IOException ignored) {}
        }
    }

    /** 内存文件管理器: 编译产物写内存, 不落盘 */
    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> buffers = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(className, baos);
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return baos;
                }
            };
        }

        Map<String, byte[]> snapshot() {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> e : buffers.entrySet()) {
                result.put(e.getKey(), e.getValue().toByteArray());
            }
            return result;
        }
    }

    private static final class CompiledClass {
        private final Map<String, byte[]> bytes;

        CompiledClass(Map<String, byte[]> bytes) {
            this.bytes = bytes;
        }

        ClassLoader newClassLoader(List<String> jars) {
            URL[] urls = new URL[jars.size()];
            try {
                for (int i = 0; i < jars.size(); i++) {
                    urls[i] = new File(jars.get(i)).toURI().toURL();
                }
            } catch (Exception e) {
                throw new RuntimeException("无效的 JAR 路径: " + e.getMessage(), e);
            }
            // 父加载器用系统加载器, 保留对 JDK / 业务 class 的可见性
            return new URLClassLoader(urls, ClassLoader.getSystemClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] b = bytes.get(name);
                    if (b != null) {
                        return defineClass(name, b, 0, b.length);
                    }
                    return super.findClass(name);
                }
            };
        }
    }
}
