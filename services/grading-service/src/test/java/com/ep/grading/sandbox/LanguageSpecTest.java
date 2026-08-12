package com.ep.grading.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-185: LanguageSpec command template + fromName tests.
 */
class LanguageSpecTest {

    @Test
    void java_compile_and_run_commands() {
        String dir = "/tmp/ep-sandbox";
        List<String> compile = LanguageSpec.JAVA.compileCommands(dir);
        assertEquals(List.of("javac", "-d", dir, dir + "/Main.java"), compile);
        List<String> run = LanguageSpec.JAVA.runCommand(dir);
        assertEquals(List.of("java", "-cp", dir, "Main"), run);
    }

    @Test
    void python_has_no_compile_step() {
        String dir = "/tmp/ep";
        assertTrue(LanguageSpec.PYTHON.compileCommands(dir).isEmpty());
        assertEquals(List.of("python3", dir + "/main.py"),
                LanguageSpec.PYTHON.runCommand(dir));
    }

    @Test
    void go_run_command_substitutes_temp_dir() {
        String dir = "/tmp/go-exec";
        assertEquals(List.of("go", "run", dir + "/main.go"),
                LanguageSpec.GO.runCommand(dir));
        assertTrue(LanguageSpec.GO.compileCommands(dir).isEmpty());
    }

    @Test
    void cpp_compile_and_run() {
        String dir = "/tmp/cpp";
        assertEquals(List.of("g++", "-o", dir + "/a.out", dir + "/main.cpp"),
                LanguageSpec.CPP.compileCommands(dir));
        assertEquals(List.of(dir + "/a.out"),
                LanguageSpec.CPP.runCommand(dir));
    }

    @Test
    void fromName_resolves_case_insensitive() {
        assertEquals(LanguageSpec.JAVA, LanguageSpec.fromName("java"));
        assertEquals(LanguageSpec.JAVA, LanguageSpec.fromName("JAVA"));
        assertEquals(LanguageSpec.JAVA, LanguageSpec.fromName(" Java "));
        assertEquals(LanguageSpec.PYTHON, LanguageSpec.fromName("python3"));
    }

    @Test
    void fromName_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> LanguageSpec.fromName("rust"));
        assertThrows(IllegalArgumentException.class, () -> LanguageSpec.fromName(""));
        assertThrows(IllegalArgumentException.class, () -> LanguageSpec.fromName(null));
    }

    @Test
    void file_names_match_spec() {
        assertEquals("Main.java", LanguageSpec.JAVA.fileName());
        assertEquals("main.py", LanguageSpec.PYTHON.fileName());
        assertEquals("main.go", LanguageSpec.GO.fileName());
        assertEquals("main.cpp", LanguageSpec.CPP.fileName());
    }

    @Test
    void file_extensions() {
        assertEquals("java", LanguageSpec.JAVA.fileExtension());
        assertEquals("py", LanguageSpec.PYTHON.fileExtension());
        assertEquals("go", LanguageSpec.GO.fileExtension());
        assertEquals("cpp", LanguageSpec.CPP.fileExtension());
        // interpreted languages have no compile step
        assertTrue(LanguageSpec.PYTHON.compileCommands("/x").isEmpty());
        assertTrue(LanguageSpec.GO.compileCommands("/x").isEmpty());
        // compiled languages have a compile step
        assertFalse(LanguageSpec.JAVA.compileCommands("/x").isEmpty());
        assertFalse(LanguageSpec.CPP.compileCommands("/x").isEmpty());
    }
}
