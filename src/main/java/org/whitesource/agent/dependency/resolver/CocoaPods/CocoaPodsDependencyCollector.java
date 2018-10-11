package org.whitesource.agent.dependency.resolver.CocoaPods;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.whitesource.agent.Constants;
import org.whitesource.agent.api.model.AgentProjectInfo;
import org.whitesource.agent.api.model.DependencyInfo;
import org.whitesource.agent.api.model.DependencyType;
import org.whitesource.agent.dependency.resolver.DependencyCollector;
import org.whitesource.agent.hash.HashCalculator;
import org.whitesource.agent.utils.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * @author raz.nitzan
 */
public class CocoaPodsDependencyCollector extends DependencyCollector {

    /* --- Static Members --- */

    private static final String PODS = "PODS";
    private static final String DEPENDENCIES = "DEPENDENCIES";
    private static final String PATTERN_DIRECT_LINE = "  -";
    private static final String PATTERN_TRANSITIVE_DEPENDENCY = "    -";
    private static final String COCOA_PODS = "CocoaPods";

    /* --- Members --- */

    private final Logger logger = LoggerFactory.getLogger(CocoaPodsDependencyCollector.class);
    private HashCalculator hashCalculator = new HashCalculator();

    /* --- public methods --- */

    public Collection<AgentProjectInfo> collectDependencies(String podFileLock) {
        Collection<DependencyInfo> dependencies = new LinkedList<>();
        List<String> directDependenciesLines = new LinkedList<>();
        List<String> allDependenciesLines = new LinkedList<>();
        if (getPodsAndDependenciesSection(directDependenciesLines, allDependenciesLines, podFileLock)) {
            dependencies.addAll(parseDependenciesLines(directDependenciesLines, allDependenciesLines, podFileLock));
        } else {
            logger.warn("Failed to parse the Podfile.lock in {}", podFileLock);
        }
        return getSingleProjectList(dependencies);
    }

    /* --- private methods --- */

    private boolean getPodsAndDependenciesSection(List<String> directDependenciesLines, List<String> allDependenciesLines, String podFileLock) {
        boolean successReadPodfile = true;
        boolean podsSection = false;
        boolean dependenciesSection = false;
        try (BufferedReader br = new BufferedReader(new FileReader(podFileLock))) {
            String line;
            logger.debug("The content of Podfile.lock - {}:", podFileLock);
            while ((line = br.readLine()) != null) {
                logger.debug(line);
                if (line.startsWith(PODS)) {
                    dependenciesSection = false;
                    podsSection = true;
                } else if (line.startsWith(DEPENDENCIES)) {
                    podsSection = false;
                    dependenciesSection = true;
                } else if (line.trim().equals(Constants.EMPTY_STRING)) {
                    podsSection = false;
                    dependenciesSection = false;
                } else if (podsSection) {
                    allDependenciesLines.add(line);
                } else if (dependenciesSection) {
                    directDependenciesLines.add(line);
                }
            }
        } catch (IOException e) {
            logger.warn("Couldn't read the Podfile.lock: {}", podFileLock);
            successReadPodfile = false;
        }
        return successReadPodfile;
    }

    private Collection<DependencyInfo> parseDependenciesLines(List<String> directDependenciesLines, List<String> allDependenciesLines, String podFileLock) {
        Collection<DependencyInfo> dependencies = new LinkedList<>();
        for (String lineDirect : directDependenciesLines) {
            if (StringUtils.isNotEmpty(lineDirect)) {
                int indexFirstBracket = lineDirect.indexOf(Constants.OPEN_BRACKET);
                String prefixToSearch = lineDirect + Constants.WHITESPACE;
                if (indexFirstBracket > -1) {
                    prefixToSearch = lineDirect.substring(0, indexFirstBracket);
                }
                DependencyInfo dependencyInfo = getDependencyInfo(allDependenciesLines, prefixToSearch, podFileLock);
                if (dependencyInfo != null) {
                    dependencies.add(dependencyInfo);
                }
            }
        }
        return dependencies;
    }

    private DependencyInfo getDependencyInfo(List<String> allDependenciesLines, String prefixToSearch, String podFileLock) {
        DependencyInfo dependency = null;
        boolean getToRightDependency = false;
        for (String line : allDependenciesLines) {
            if (line.startsWith(PATTERN_DIRECT_LINE) && line.startsWith(prefixToSearch)) {
                getToRightDependency = true;
                dependency = createDependencyFromLine(line, podFileLock);
            } else if (getToRightDependency && line.startsWith(PATTERN_DIRECT_LINE)) {
                return dependency;
            } else if (getToRightDependency && line.startsWith(PATTERN_TRANSITIVE_DEPENDENCY)) {
                if (dependency != null) {
                    int indexFirstBracket = line.indexOf(Constants.OPEN_BRACKET);
                    String newPrefixToSearch = line.substring(2) + Constants.WHITESPACE;
                    if (indexFirstBracket > -1) {
                        newPrefixToSearch = line.substring(2, indexFirstBracket);
                    }
                    DependencyInfo childDependency = getDependencyInfo(allDependenciesLines, newPrefixToSearch, podFileLock);
                    dependency.getChildren().add(childDependency);
                }
            }
        }
        return dependency;
    }

    private DependencyInfo createDependencyFromLine(String line, String podFileLock) {
        int indexOpenBracket = line.indexOf(Constants.OPEN_BRACKET);
        // get the name and version from line like this:   - AFNetworking (2.2.1):
        String name = line.substring(line.indexOf(Constants.DASH) + 2, indexOpenBracket - 1);
        if (name.startsWith(Constants.QUOTATION_MARK)) {
            name = name.substring(1);
        }
        String version = line.substring(indexOpenBracket + 1, line.lastIndexOf(Constants.CLOSE_BRACKET));
        DependencyInfo dependency = new DependencyInfo();
        dependency.setArtifactId(name);
        dependency.setVersion(version);
        dependency.setGroupId(name);
        dependency.setFilename(name + Constants.DASH + version);
        dependency.setSystemPath(podFileLock);
        // This is a fake sha1 - concatenation of name + _ + version + _ + CocoaPods, for example: QueryKit/QueryKit_0.10.0_CocoaPods => sha1: ed0b4691a4c3761232f07b544989b7f55b6826c3
        String sha1 = null;
        String sha1ToCalc = name + Constants.UNDERSCORE + version + Constants.UNDERSCORE + COCOA_PODS;
        try {
            sha1 = this.hashCalculator.calculateByteArraySHA1(sha1ToCalc.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.debug("Failed to calculate sha1 of: {}", sha1ToCalc);
        }
        if (sha1 != null) {
            dependency.setSha1(sha1);
        }
        dependency.setDependencyType(DependencyType.COCOAPODS);
        return dependency;
    }
}
