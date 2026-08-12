package com.ep.grading.sandbox;

import java.util.List;

/**
 * D-185: Language specifications for the sandbox executor.
 * <p>
 * Each enum constant describes how to compile and run a single source file for
 * its language. The {@code {tempDir}} placeholder in command templates is
 * substituted by {@link ProcessSandboxExecutor} with the per-execution temp
 * directory absolute path.
 * <p>
 * Iron Law 8 (Option C): commands invoke system toolchains ({@code javac},
 * {@code python3}, {@code go}, {@code g++}) directly via {@link ProcessBuilder}.
 * No Firecracker/Docker dependency is required, so the sandbox works in the
 * offline AOT build environment.
 */
public enum LanguageSpec {

    /**
     * Java — compile with {@code javac}, run with {@code java}.
     * Source file: {@code Main.java}, public class {@code Main}.
     */
    JAVA("java",
            List.of("javac", "-d", "{tempDir}", "{tempDir}/Main.java"),
            List.of("java", "-cp", "{tempDir}", "Main"),
            "java", "Main.java"),

    /**
     * Python 3 — interpreted, no compile step.
     */
    PYTHON("python3",
            List.of(),
            List.of("python3", "{tempDir}/main.py"),
            "py", "main.py"),

    /**
     * Go — run with {@code go run}. No explicit compile step (the toolchain
     * builds a transient binary).
     */
    GO("go",
            List.of(),
            List.of("go", "run", "{tempDir}/main.go"),
            "go", "main.go"),

    /**
     * C++ — compile with {@code g++} to a binary, then execute the binary.
     */
    CPP("cpp",
            List.of("g++", "-o", "{tempDir}/a.out", "{tempDir}/main.cpp"),
            List.of("{tempDir}/a.out"),
            "cpp", "main.cpp");

    private final String displayName;
    private final List<String> compileTemplate;
    private final List<String> runTemplate;
    private final String fileExtension;
    private final String fileName;

    LanguageSpec(String displayName,
                 List<String> compileTemplate,
                 List<String> runTemplate,
                 String fileExtension,
                 String fileName) {
        this.displayName = displayName;
        this.compileTemplate = compileTemplate;
        this.runTemplate = runTemplate;
        this.fileExtension = fileExtension;
        this.fileName = fileName;
    }

    /** Human-readable name (also the value accepted by {@link ExecutionRequest#language()}). */
    public String displayName() {
        return displayName;
    }

    /** Source file extension without the leading dot, e.g. {@code java}. */
    public String fileExtension() {
        return fileExtension;
    }

    /** Source file name to write inside the temp dir, e.g. {@code Main.java}. */
    public String fileName() {
        return fileName;
    }

    /**
     * The compile command for this language, with {@code {tempDir}} substituted
     * by {@code tempDir}. Empty list for interpreted languages.
     */
    public List<String> compileCommands(String tempDir) {
        return substitute(compileTemplate, tempDir);
    }

    /** The run command for this language, with {@code {tempDir}} substituted. */
    public List<String> runCommand(String tempDir) {
        return substitute(runTemplate, tempDir);
    }

    /**
     * Resolve a language name (case-insensitive) to a {@link LanguageSpec}.
     *
     * @param name language name from {@link ExecutionRequest#language()}
     * @return the matching spec
     * @throws IllegalArgumentException if the language is unknown
     */
    public static LanguageSpec fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("language name is blank");
        }
        String upper = name.trim().toUpperCase();
        for (LanguageSpec spec : values()) {
            if (spec.name().equals(upper) || spec.displayName.equalsIgnoreCase(name.trim())) {
                return spec;
            }
        }
        throw new IllegalArgumentException("unsupported language: " + name);
    }

    private static List<String> substitute(List<String> template, String tempDir) {
        return template.stream()
                .map(arg -> arg.replace("{tempDir}", tempDir))
                .toList();
    }
}
