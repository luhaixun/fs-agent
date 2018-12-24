/**
 * Copyright (C) 2017 WhiteSource Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.whitesource.agent.dependency.resolver.npm;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.eclipse.jgit.util.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.whitesource.utils.Constants;
import org.whitesource.agent.api.model.AgentProjectInfo;
import org.whitesource.agent.api.model.DependencyInfo;
import org.whitesource.agent.api.model.DependencyType;
import org.whitesource.agent.dependency.resolver.AbstractDependencyResolver;
import org.whitesource.agent.dependency.resolver.BomFile;
import org.whitesource.agent.dependency.resolver.ResolutionResult;
import org.whitesource.agent.dependency.resolver.bower.BowerDependencyResolver;;
import org.whitesource.agent.utils.AddDependencyFileRecursionHelper;
import org.whitesource.utils.files.FilesScanner;
import org.whitesource.utils.logger.LoggerFactory;
import org.whitesource.utils.StatusCode;

import javax.ws.rs.core.MediaType;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Dependency Resolver for NPM projects.
 *
 * @author eugen.horovitz
 */
public class NpmDependencyResolver extends AbstractDependencyResolver {

    /* --- Static members --- */

    private final Logger logger = LoggerFactory.getLogger(NpmDependencyResolver.class);

    private static final String PACKAGE_JSON = "package.json";
    private static final String TYPE_SCRIPT_EXTENSION = ".ts";
    private static final String TSX_EXTENSION = ".tsx";
    private static final String JS_PATTERN = "**/*.js";
    private static final String EXAMPLE = "**/example/**/";
    private static final String EXAMPLES = "**/examples/**/";
    private static final String WS_BOWER_FOLDER = "**/.ws_bower/**/";
    private static final String TEST = "**/test/**/";
    private static final long NPM_DEFAULT_LS_TIMEOUT = 60;
    private static final String VERSIONS = "versions";
    private static final String DIST = "dist";
    private static final String SHASUM = "shasum";

    private static final String EXCLUDE_TOP_FOLDER = "node_modules";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer";
    private static final String BASIC = "Basic";
    private static final int NUM_THREADS = 8;
    private static final String URL_SLASH = "%2F";

    /* --- Members --- */

    private final NpmLsJsonDependencyCollector bomCollector;
    private final NpmBomParser bomParser;
    private final boolean ignoreSourceFiles;
    private final boolean runPreStep;
    private final FilesScanner filesScanner;
    private final String npmAccessToken;
    private final boolean npmYarnProject;

    /* --- Constructor --- */

    public NpmDependencyResolver(boolean includeDevDependencies, boolean ignoreSourceFiles, long npmTimeoutDependenciesCollector,
                                 boolean runPreStep, boolean npmIgnoreNpmLsErrors, String npmAccessToken, boolean npmYarnProject, boolean ignoreScripts) {
        super();
        bomCollector = npmYarnProject ? new YarnDependencyCollector(includeDevDependencies, npmTimeoutDependenciesCollector, ignoreSourceFiles, ignoreScripts) : new NpmLsJsonDependencyCollector(includeDevDependencies, npmTimeoutDependenciesCollector, npmIgnoreNpmLsErrors, ignoreScripts);
        bomParser = new NpmBomParser();
        this.ignoreSourceFiles = ignoreSourceFiles;
        this.runPreStep = runPreStep;
        this.filesScanner = new FilesScanner();
        this.npmAccessToken = npmAccessToken;
        this.npmYarnProject = npmYarnProject;
    }

    public NpmDependencyResolver(boolean runPreStep, String npmAccessToken, boolean bowerIgnoreSourceFiles) {
        this(false,bowerIgnoreSourceFiles, NPM_DEFAULT_LS_TIMEOUT , runPreStep, false, npmAccessToken, false, false);
    }

    /* --- Overridden methods --- */

    @Override
    protected Collection<String> getLanguageExcludes() {
        // NPM can contain files generated by the WhiteSource Bower plugin
        Set<String> excludes = new HashSet<>();
        excludes.add(BowerDependencyResolver.WS_BOWER_FILE2);
        excludes.add(BowerDependencyResolver.WS_BOWER_FILE1);
        return excludes;
    }

    @Override
    public String[] getBomPattern() {
        return new String[]{Constants.PATTERN + PACKAGE_JSON};
    }

    @Override
    protected ResolutionResult resolveDependencies(String projectFolder, String topLevelFolder, Set<String> bomFiles) {
        if (runPreStep) {
            getDependencyCollector().executePreparationStep(topLevelFolder);
            String[] excludesArray = new String[getExcludes().size()];
            excludesArray = getExcludes().toArray(excludesArray);
            String[] otherBomFiles = filesScanner.getDirectoryContent(topLevelFolder, getBomPattern(), excludesArray, false, false);
            Arrays.stream(otherBomFiles).forEach(file -> bomFiles.add(Paths.get(topLevelFolder, file).toString()));
        }

        logger.debug("Attempting to parse package.json files");
        // parse package.json files
        Collection<BomFile> parsedBomFiles = new LinkedList<>();

        Map<File, List<File>> mapBomFiles = bomFiles.stream().map(file -> new File(file)).collect(Collectors.groupingBy(File::getParentFile));

        List<File> files = mapBomFiles.entrySet().stream().map(entry -> {
            if (entry.getValue().size() > 1) {
                return entry.getValue().stream().filter(this::fileShouldBeParsed).findFirst().get();
            } else {
                return entry.getValue().stream().findFirst().get();
            }
        }).collect(Collectors.toList());

        files.forEach(bomFile -> {
            BomFile parsedBomFile = getBomParser().parseBomFile(bomFile.getAbsolutePath());
            if (parsedBomFile != null && parsedBomFile.isValid()) {
                parsedBomFiles.add(parsedBomFile);
            }
        });

        logger.debug("Trying to collect dependencies via 'npm ls'");
        // try to collect dependencies via 'npm ls'
        List<String> bomFilesNames = parsedBomFiles.stream().map(BomFile::getLocalFileName).filter(s -> s.contains(EXCLUDE_TOP_FOLDER) == false).collect(Collectors.toList());
        Collection<AgentProjectInfo> projects = getDependencyCollector().collectDependencies(topLevelFolder);
        // in case there is more than one module (i.e. - many package.json files outside of node_modules folder) - collect their dependencies as well
        bomFilesNames.stream().forEach(bomFilePath -> {
            bomFilePath = bomFilePath.substring(0, bomFilePath.lastIndexOf(fileSeparator));
            if (bomFilePath.equals(topLevelFolder) == false){
                projects.addAll(getDependencyCollector().collectDependencies(bomFilePath));
            }
        });

        Collection<DependencyInfo> dependencies = projects.stream().flatMap(project -> project.getDependencies().stream()).collect(Collectors.toList());
        // this code turn the dependencies tree recursively into a flat-list,
        // so that each dependency has its dependencyFile set
        dependencies.stream()
                .flatMap(AddDependencyFileRecursionHelper::flatten)
                .forEach(dependencyInfo -> dependencyInfo.setDependencyFile(projectFolder + fileSeparator + PACKAGE_JSON));

        boolean lsSuccess = !getDependencyCollector().getNpmLsFailureStatus();
        // flag that indicates if the number of the dependencies is zero and npm ls succeeded
        boolean zeroDependenciesList = false;
        if (lsSuccess) {
            logger.debug("'npm ls succeeded");
            if (!dependencies.isEmpty()) {
                handleLsSuccess(parsedBomFiles, dependencies, npmAccessToken);
            } else {
                zeroDependenciesList = true;
            }
        } else {
            logger.debug("'npm ls failed");
            dependencies.addAll(collectPackageJsonDependencies(parsedBomFiles));
        }
        //removeDependenciesWithoutSha1(dependencies);
        logger.debug("Creating excludes for .js files upon finding NPM dependencies");
        // create excludes for .js files upon finding NPM dependencies
        List<String> excludes = new LinkedList<>();
        if (!dependencies.isEmpty() || zeroDependenciesList) {
            if (ignoreSourceFiles ) {
                //return excludes.stream().map(exclude -> finalRes + exclude).collect(Collectors.toList());
                excludes.addAll(normalizeLocalPath(projectFolder, topLevelFolder, Arrays.asList(JS_PATTERN, Constants.PATTERN + TYPE_SCRIPT_EXTENSION,
                        Constants.PATTERN + TSX_EXTENSION), null));
            } else {
                excludes.addAll(normalizeLocalPath(projectFolder, topLevelFolder, Arrays.asList(JS_PATTERN, Constants.PATTERN + TYPE_SCRIPT_EXTENSION,
                        Constants.PATTERN + TSX_EXTENSION), EXCLUDE_TOP_FOLDER));
            }
        }
        return new ResolutionResult(dependencies, excludes, getDependencyType(), topLevelFolder);
    }

    @Override
    protected Collection<String> getExcludes() {
        Set<String> excludes = new HashSet<>();
        String bomPattern = getBomPattern()[0];
        excludes.add(EXAMPLE + bomPattern);
        excludes.add(EXAMPLES + bomPattern);
        excludes.add(WS_BOWER_FOLDER + bomPattern);
        excludes.add(TEST + bomPattern);

        excludes.addAll(getLanguageExcludes());
        return excludes;
    }

    @Override
    public Collection<String> getSourceFileExtensions() {
        return Arrays.asList(Constants.JS_EXTENSION, TYPE_SCRIPT_EXTENSION, TSX_EXTENSION);
    }

    /* --- Protected methods --- */

    // These methods are relevant only for npm and bower

    protected String getPreferredFileName() {
        return PACKAGE_JSON;
    }

    protected NpmBomParser getBomParser() {
        return bomParser;
    }

    protected DependencyType getDependencyType() {
        return DependencyType.NPM;
    }

    protected NpmLsJsonDependencyCollector getDependencyCollector() {
        return bomCollector;
    }

    protected boolean isMatchChildDependency(DependencyInfo childDependency, String name, String version) {
        return childDependency.getArtifactId().equals(NpmBomParser.getNpmArtifactId(name, version));
    }

    @Override
    protected String getDependencyTypeName() {
        return DependencyType.NPM.name();
    }

    protected void enrichDependency(DependencyInfo dependency, BomFile packageJson, String npmAccessToken) {
        String sha1 = packageJson.getSha1();
        String registryPackageUrl = packageJson.getRegistryPackageUrl();
        if (StringUtils.isEmptyOrNull(sha1) && !StringUtils.isEmptyOrNull(registryPackageUrl)) {
            sha1 = getSha1FromRegistryPackageUrl(registryPackageUrl, packageJson.isScopedPackage(), packageJson.getVersion(), packageJson.getRegistryType(), npmAccessToken);
        }
        dependency.setSha1(sha1);
        dependency.setGroupId(packageJson.getName());
        dependency.setArtifactId(packageJson.getFileName());
        dependency.setVersion(packageJson.getVersion());
        dependency.setSystemPath(packageJson.getLocalFileName());
        dependency.setFilename(packageJson.getLocalFileName());
        dependency.setDependencyType(getDependencyType());
    }

    /* --- Private methods --- */

    private String getSha1FromRegistryPackageUrl(String registryPackageUrl, boolean isScopeDep, String versionOfPackage, RegistryType registryType, String npmAccessToken) {

        String uriScopeDep = registryPackageUrl;
        if (isScopeDep) {
            try {
                uriScopeDep = registryPackageUrl.replace(BomFile.DUMMY_PARAMETER_SCOPE_PACKAGE, URL_SLASH);
            } catch (Exception e) {
                logger.warn("Failed creating uri of {}", registryPackageUrl);
                return Constants.EMPTY_STRING;
            }
        }


        String responseFromRegistry = null;
        try {
            Client client = Client.create();
            ClientResponse response;
            WebResource resource;
            resource = client.resource(uriScopeDep);
            if (StringUtils.isEmptyOrNull(npmAccessToken)) {
                response = resource.accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
                logger.debug("npm.accessToken is not defined");
            } else {
                logger.debug("npm.accessToken is defined");
                if (registryType == RegistryType.VISUAL_STUDIO) {
                    String userCredentials = BEARER + Constants.COLON + npmAccessToken;
                    String basicAuth = BASIC + Constants.WHITESPACE + new String(Base64.getEncoder().encode(userCredentials.getBytes()));
                    response = resource.accept(MediaType.APPLICATION_JSON).header("Authorization", basicAuth).get(ClientResponse.class);
                } else {
                    // Bearer authorization
                    String userCredentials = BEARER + Constants.WHITESPACE + npmAccessToken;
                    response = resource.accept(MediaType.APPLICATION_JSON).header("Authorization", userCredentials).get(ClientResponse.class);
                }
            }
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                responseFromRegistry = response.getEntity(String.class);
            } else {
                logger.debug("Got {} status code from registry using the url {}.", response.getStatus(), uriScopeDep);
            }
        } catch (Exception e) {
            logger.warn("Could not reach the registry using the URL: {}. Got an error: {}", registryPackageUrl, e.getMessage());
            return Constants.EMPTY_STRING;
        }
        if (responseFromRegistry == null) {
            return Constants.EMPTY_STRING;
        }
        JSONObject jsonRegistry = new JSONObject(responseFromRegistry);
        String shasum;
        if (isScopeDep) {
            shasum = jsonRegistry.getJSONObject(VERSIONS).getJSONObject(versionOfPackage).getJSONObject(DIST).getString(SHASUM);
        } else {
            shasum = jsonRegistry.getJSONObject(DIST).getString(SHASUM);
        }
        return shasum;
    }

    /**
     * Collect dependencies from package.json files - without 'npm ls'
     */
    private Collection<DependencyInfo> collectPackageJsonDependencies(Collection<BomFile> packageJsons) {
        Collection<DependencyInfo> dependencies = new LinkedList<>();
        ConcurrentHashMap<DependencyInfo, BomFile> dependencyPackageJsonMap = new ConcurrentHashMap<>();
        ExecutorService executorService = Executors.newWorkStealingPool(NUM_THREADS);
        Collection<EnrichDependency> threadsCollection = new LinkedList<>();
        for (BomFile packageJson : packageJsons) {
            if (packageJson != null && packageJson.isValid()) {
                // do not add new dependencies if 'npm ls' already returned all
                DependencyInfo dependency = new DependencyInfo();
                dependencies.add(dependency);
                threadsCollection.add(new EnrichDependency(packageJson, dependency, dependencyPackageJsonMap, npmAccessToken));
                logger.debug("Collect package.json of the dependency in the file: {}", dependency.getFilename());
            }
        }
        runThreadCollection(executorService, threadsCollection);
        logger.debug("set hierarchy of the dependencies");
        // remove duplicates dependencies
        Map<String, DependencyInfo> existDependencies = new HashMap<>();
        Map<DependencyInfo, BomFile> dependencyPackageJsonMapWithoutDuplicates = new HashMap<>();
        for (Map.Entry<DependencyInfo, BomFile> entry : dependencyPackageJsonMap.entrySet()) {
            DependencyInfo keyDep = entry.getKey();
            String key = keyDep.getSha1() + keyDep.getVersion() + keyDep.getArtifactId();
            if (!existDependencies.containsKey(key)) {
                existDependencies.put(key, keyDep);
                dependencyPackageJsonMapWithoutDuplicates.put(keyDep, entry.getValue());
            }
        }
        setHierarchy(dependencyPackageJsonMapWithoutDuplicates, existDependencies);
        return existDependencies.values();
    }

    private void runThreadCollection(ExecutorService executorService, Collection<EnrichDependency> threadsCollection) {
        try {
            executorService.invokeAll(threadsCollection);
            executorService.shutdown();
        } catch (InterruptedException e) {
            logger.error("One of the threads was interrupted, please try to scan again the project. Error: {}", e.getMessage());
            System.exit(StatusCode.ERROR.getValue());
        }
    }

    private boolean fileShouldBeParsed(File file) {
        return (file.getAbsolutePath().endsWith(getPreferredFileName()));
    }

    private void setHierarchy(Map<DependencyInfo, BomFile> dependencyPackageJsonMap, Map<String, DependencyInfo> existDependencies) {
        dependencyPackageJsonMap.forEach((dependency, packageJson) -> {
            packageJson.getDependencies().forEach((name, version) -> {
                Optional<DependencyInfo> childDep = dependencyPackageJsonMap.keySet().stream()
                        .filter(childDependency -> isMatchChildDependency(childDependency, name, version))
                        .findFirst();

                if (childDep.isPresent()) {
                    DependencyInfo childDepGet = childDep.get();
                    String key = childDepGet.getSha1() + childDepGet.getVersion() + childDepGet.getArtifactId();
                    if (!existDependencies.containsKey(key)) {
                        dependency.getChildren().add(childDep.get());
                        existDependencies.put(key, childDepGet);
                    }
                }
            });
        });
    }

    private void handleLsSuccess(Collection<BomFile> packageJsonFiles, Collection<DependencyInfo> dependencies, String npmAccessToken) {
        Map<String, BomFile> resultFiles = packageJsonFiles.stream()
                .filter(packageJson -> packageJson != null && packageJson.isValid())
                .filter(distinctByKey(BomFile::getFileName))
                .collect(Collectors.toMap(BomFile::getUniqueDependencyName, Function.identity()));

        logger.debug("Handling all dependencies");
        Collection<EnrichDependency> threadsCollection = new LinkedList<>();
        dependencies.forEach(dependency -> handleLSDependencyRecursivelyImpl(dependency, resultFiles, threadsCollection, npmAccessToken));
        ExecutorService executorService = Executors.newWorkStealingPool(NUM_THREADS);
        runThreadCollection(executorService, threadsCollection);
    }

    private void handleLSDependencyRecursivelyImpl(DependencyInfo dependency, Map<String, BomFile> resultFiles, Collection<EnrichDependency> threadsCollection, String npmAccessToken) {
        String uniqueName = BomFile.getUniqueDependencyName(dependency.getGroupId(), dependency.getVersion());
        BomFile packageJson = resultFiles.get(uniqueName);
        if (packageJson != null) {
            threadsCollection.add(new EnrichDependency(packageJson, dependency, npmAccessToken));
        } else {
            logger.debug("Dependency {} could not be retrieved. 'package.json' could not be found", dependency.getArtifactId());
        }
        logger.debug("handle the children dependencies in the file: {}", dependency.getFilename());
        dependency.getChildren().forEach(childDependency -> handleLSDependencyRecursivelyImpl(childDependency, resultFiles, threadsCollection, npmAccessToken));
    }

    // currently deprecated - not relevant
    private void removeDependenciesWithoutSha1(Collection<DependencyInfo> dependencies){
        Collection<DependencyInfo> childDependencies = new ArrayList<>();
        for (Iterator<DependencyInfo> iterator = dependencies.iterator(); iterator.hasNext();){
            DependencyInfo dependencyInfo = iterator.next();
            if (dependencyInfo.getSha1().isEmpty()){
                childDependencies.addAll(dependencyInfo.getChildren());
                iterator.remove();
            }
        }
        dependencies.addAll(childDependencies);
    }

    private <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    /* --- Nested classes --- */

    class EnrichDependency implements Callable<Void> {

        /* --- Members --- */

        private BomFile packageJson;
        private DependencyInfo dependency;
        private ConcurrentHashMap<DependencyInfo, BomFile> dependencyPackageJsonMap;
        private String npmAccessToken;

        /* --- Constructors --- */

        public EnrichDependency(BomFile packageJson, DependencyInfo dependency, String npmAccessToken) {
            this.packageJson = packageJson;
            this.dependency = dependency;
            this.dependencyPackageJsonMap = null;
            this.npmAccessToken = npmAccessToken;
        }

        public EnrichDependency(BomFile packageJson, DependencyInfo dependency,
                                ConcurrentHashMap<DependencyInfo, BomFile> dependencyPackageJsonMap, String npmAccessToken) {
            this.packageJson = packageJson;
            this.dependency = dependency;
            this.dependencyPackageJsonMap = dependencyPackageJsonMap;
            this.npmAccessToken = npmAccessToken;
        }

        /* --- Overridden methods --- */

        @Override
        public Void call() {
            enrichDependency(this.dependency, this.packageJson, this.npmAccessToken);
            if (dependencyPackageJsonMap != null) {
                dependencyPackageJsonMap.putIfAbsent(this.dependency, this.packageJson);
            }
            return null;
        }
    }
}