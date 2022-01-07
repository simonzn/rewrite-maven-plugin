package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourceParser {
    private final Log logger;
    private final Collection<String> exclusions;
    private final int sizeThresholdMb;

    public ResourceParser(Log logger, Collection<String> exclusions, int thresholdMb) {
        this.logger = logger;
        this.exclusions = exclusions;
        sizeThresholdMb = thresholdMb;
    }

    public List<SourceFile> parse(Path baseDir, Path searchDir, Collection<Path> alreadyParsed) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        if (!searchDir.toFile().exists()) {
            return sourceFiles;
        }
        Consumer<Throwable> errorConsumer = t -> logger.error("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        sourceFiles.addAll(parseSourceFiles(baseDir, new JsonParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new XmlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new YamlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new PropertiesParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, HclParser.builder().build(), searchDir, alreadyParsed, ctx));

        return sourceFiles;
    }

    public <S extends SourceFile> List<S> parseSourceFiles(Path baseDir,
                                                           Parser<S> parser,
                                                           Path searchDir,
                                                           Collection<Path> alreadyParsed,
                                                           ExecutionContext ctx) {

        BiPredicate<Path, BasicFileAttributes> sourceMatcher = (path, attrs) -> {
            if (path.toString().contains("/target/") || path.toString().contains("/build/")
                    || path.toString().contains("/out/") || path.toString().contains("/node_modules/") || path.toString().contains("/.metadata/")) {
                return false;
            }
            for (String exclusion : exclusions) {
                PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                if (matcher.matches(baseDir.relativize(path))) {
                    return false;
                }
            }

            if (alreadyParsed.contains(searchDir.relativize(path))) {
                return false;
            }

            if (attrs.isDirectory() || attrs.size() == 0) {
                return false;
            }
            if (sizeThresholdMb > 0 && attrs.size() > sizeThresholdMb * 1024L * 1024L) {
                alreadyParsed.add(path);
                //noinspection StringConcatenationMissingWhitespace
                logger.info("Skipping parsing " + path + " as its size + " + attrs.size() / (1024L * 1024L) +
                        "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                return false;
            }
            return parser.accept(path);
        };

        List<Path> sources;
        try (Stream<Path> files = Files.find(searchDir, 16, sourceMatcher)) {
            sources = files.collect(Collectors.toList());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
        return parser.parse(sources, baseDir, ctx);
    }
}
