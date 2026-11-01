package com.jenkinschina.atom.compressartifacts;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Packs the files a build produced into one archive.
 *
 * <p>The compress-artifacts plugin exists because a workspace full of small files is slow to archive and slow to
 * copy; the step is a zip with a predictable layout. Entries are written in sorted order with a fixed timestamp so
 * the same input produces the same bytes, which is what makes an archive comparable between builds.
 */
public class CompressArtifactsAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String patterns = context.getParam("patterns", "**/*");
        String output = context.getParam("outputFile", "artifacts.zip");
        String baseDir = context.getParam("baseDir", "").trim();
        String exclude = context.getParam("exclude", "");
        boolean deleteSource = context.getBooleanParam("deleteSource", false);

        Path base = baseDir.isEmpty() ? workspace : workspace.resolve(baseDir).normalize();
        if (!base.startsWith(workspace) || !Files.isDirectory(base)) {
            return AtomResult.error(ErrorType.USER, 40001, "baseDir must be a directory inside the workspace");
        }
        Path target = workspace.resolve(output).normalize();
        if (!target.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40002, "outputFile must stay inside the workspace");
        }

        List<Path> files = collect(base, patterns, exclude, target);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003,
                    "no file matched '" + patterns + "'" + (exclude.isBlank() ? "" : " (excluding " + exclude + ")"));
        }

        Files.createDirectories(target.getParent() == null ? workspace : target.getParent());
        long rawBytes = 0;
        for (Path file : files) {
            rawBytes += Files.size(file);
        }
        try (OutputStream fileOut = Files.newOutputStream(target);
                ZipWriter zip = new ZipWriter(new BufferedOutputStream(fileOut))) {
            for (Path file : files) {
                zip.add(file, base.relativize(file).toString().replace('\\', '/'));
            }
        }

        long zipBytes = Files.size(target);
        double ratio = rawBytes == 0 ? 0 : 100.0 * zipBytes / rawBytes;
        context.log(String.format(Locale.ROOT, "[zip] %d files, %d -> %d bytes (%.1f%%) into %s",
                files.size(), rawBytes, zipBytes, ratio, output));

        if (deleteSource) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            context.log("[zip] removed " + files.size() + " source file(s)");
        }

        return AtomResult.success()
                // declare the archive as an artifact, or the build page shows a file nobody can download
                .artifact("archiveFile", output)
                .data("filesAdded", String.valueOf(files.size()))
                .data("rawBytes", String.valueOf(rawBytes))
                .data("archiveBytes", String.valueOf(zipBytes))
                .data("ratio", String.format(Locale.ROOT, "%.2f", ratio))
                .data("outputFile", output);
    }



    /**
     * Glob to regex, with {@code **} crossing directories and {@code *} not. {@code **}{@code /} becomes an optional
     * directory prefix: without that, {@code **} won't match {@code *.log} for a file at the top level, which is the
     * same trap that bit the files-tool atom.
     */
    static String globToRegex(String pattern) {
        String regex = pattern.replace(".", "\\.")
                .replace("**/", "\u0001")
                .replace("**", "\u0002")
                .replace("*", "[^/]*")
                .replace("?", "[^/]")
                .replace("\u0001", "(?:.*/)?")
                .replace("\u0002", ".*");
        return regex;
    }


    /**
     * The runner keeps {@code input.json}, {@code .secrets.json} and {@code output.json} under
     * {@code .atoms/<runId>/}. A {@code **}{@code /*} pattern would otherwise pack a credential file into an archive
     * that gets published, so the runner's scratch directory is never part of the payload.
     */
    static boolean isRunnerScratch(String relative) {
        return relative.startsWith(".atoms/") || relative.equals(".atoms")
                || relative.endsWith("/.secrets.json") || relative.equals(".secrets.json");
    }

    private static List<Path> collect(Path base, String patterns, String exclude, Path target) throws IOException {
        Set<Path> matches = new LinkedHashSet<>();
        for (String pattern : patterns.split("[,\\n]")) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (Path found : Glob.find(base, trimmed)) {
                if (Files.isRegularFile(found)) {
                    matches.add(found.normalize());
                }
            }
            Path literal = base.resolve(trimmed).normalize();
            if (literal.startsWith(base) && Files.isRegularFile(literal)) {
                matches.add(literal);
            }
        }
        List<String> excluded = new ArrayList<>();
        for (String part : exclude.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                excluded.add(trimmed);
            }
        }
        List<Path> kept = new ArrayList<>();
        for (Path candidate : matches) {
            if (candidate.equals(target)) {
                continue;   // never pack the archive into itself
            }
            String relativePath = base.relativize(candidate).toString().replace('\\', '/');
            if (isRunnerScratch(relativePath)) {
                continue;   // the runner's own input/secrets must never end up in a shippable archive
            }
            String relative = base.relativize(candidate).toString().replace('\\', '/');
            boolean skip = false;
            for (String pattern : excluded) {
                String regex = globToRegex(pattern);
                if (relative.matches(regex)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                kept.add(candidate);
            }
        }
        kept.sort((a, b) -> base.relativize(a).toString().compareTo(base.relativize(b).toString()));
        return kept;
    }
}
