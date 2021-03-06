package io.jshift.kit.build.service.docker;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import io.jshift.kit.build.api.BuildContext;
import io.jshift.kit.build.api.BuildService;
import io.jshift.kit.build.api.RegistryService;
import io.jshift.kit.build.service.docker.access.BuildOptions;
import io.jshift.kit.build.service.docker.access.DockerAccess;
import io.jshift.kit.build.service.docker.access.DockerAccessException;
import io.jshift.kit.common.KitLogger;
import io.jshift.kit.common.TimeUtil;
import io.jshift.kit.config.image.ImageConfiguration;
import io.jshift.kit.config.image.ImageName;
import io.jshift.kit.config.image.build.AssemblyConfiguration;
import io.jshift.kit.config.image.build.BuildConfiguration;
import io.jshift.kit.config.image.build.CleanupMode;
import io.jshift.kit.config.image.build.DockerFileBuilder;
import io.jshift.kit.config.image.build.ImagePullPolicy;
import org.apache.maven.plugin.MojoExecutionException;


public class DockerBuildService implements BuildService {

    public static final String DEFAULT_DATA_BASE_IMAGE = "busybox:latest";

    private final DockerAccess docker;
    private final RegistryService registryService;
    private final KitLogger log;

    public DockerBuildService(DockerAccess docker, RegistryService registryService, KitLogger log) {
        this.docker = docker;
        this.registryService = registryService;
        this.log = log;
    }

    /**
     * Pull the base image if needed and run the build.
     *
     * @param imageConfig the image configuration
     * @param buildContext the build context
     */
    @Override
    public void buildImage(ImageConfiguration imageConfig, BuildContext buildContext, Map<String, String> buildArgs)
        throws IOException {
        try {
            // Call a pre-hook to the build
            autoPullBaseImageIfRequested(imageConfig, buildContext);

            String imageName = imageConfig.getName();
            ImageName.validate(imageName);
            BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();

            // Load an archive if present
            if (buildConfig.getDockerArchive() != null) {
                loadImageFromArchive(imageName, buildContext, buildConfig.getDockerArchive());
                return;
            }

            // Get old image id (if requested
            Optional<String> oldImageId = getOldImageId(imageName, buildConfig);

            // Create an archive usable for sending to the Docker daemon
            File dockerArchive = createDockerContextArchive(imageConfig, buildContext);

            // Prepare options for building against a Docker daemon and do the build
            String newImageId = build(imageConfig,
                    getBuildArgsFromProperties(buildContext, buildArgs),
                    dockerArchive);

            // Remove the image if requested
            if (oldImageId.isPresent() && !oldImageId.get().equals(newImageId)) {
                removeOldImage(imageConfig, oldImageId.get());
            }
        } catch (Exception exception) {
            throw new IOException(exception);
        }
    }

    public void tagImage(String imageName, ImageConfiguration imageConfig) throws DockerAccessException {
        List<String> tags = imageConfig.getBuildConfiguration().getTags();
        if (!tags.isEmpty()) {
            log.info("%s: Tag with %s", imageConfig.getDescription(), String.join(",",tags));

            for (String tag : tags) {
                if (tag != null) {
                    docker.tag(imageName, new ImageName(imageName, tag).getFullName(), true);
                }
            }

            log.debug("Tagging image successful!");
        }
    }


    private void autoPullBaseImageIfRequested(ImageConfiguration imageConfig, BuildContext buildContext) throws IOException, MojoExecutionException {
        BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();

        if (buildConfig.getDockerArchive() != null) {
            // No auto pull needed in archive mode
            return;
        }

        String fromImage;
        if (buildConfig.isDockerFileMode()) {
            fromImage = extractBaseFromDockerfile(buildConfig, buildContext);
        } else {
            fromImage = extractBaseFromConfiguration(buildConfig);
        }
        if (fromImage != null && !"scratch".equals(fromImage)) {

            ImagePullPolicy imagePullPolicy =
                buildConfig.getImagePullPolicy() != null ?
                    createPullPolicy(buildConfig.getImagePullPolicy()) :
                    buildContext.getRegistryContext().getDefaultImagePullPolicy();

            registryService.pullImage(fromImage, imagePullPolicy, buildContext.getRegistryContext());
        }
    }

    private ImagePullPolicy createPullPolicy(String imagePullPolicy) {
        if (imagePullPolicy != null) {
            return ImagePullPolicy.fromString(imagePullPolicy);
        }
        return ImagePullPolicy.IfNotPresent;
    }


    private String extractBaseFromConfiguration(BuildConfiguration buildConfig) {
        String fromImage;
        fromImage = buildConfig.getFrom();
        if (fromImage == null) {
            AssemblyConfiguration assemblyConfig = buildConfig.getAssemblyConfiguration();
            if (assemblyConfig == null) {
                fromImage = DEFAULT_DATA_BASE_IMAGE;
            }
        }
        return fromImage;
    }

    private String extractBaseFromDockerfile(BuildConfiguration buildConfig, BuildContext ctx) {
        String fromImage;
        try {
            final File fullDockerFilePath = ctx.inSourceDir(buildConfig.calculateDockerFilePath().getPath());
            fromImage = extractBaseImage(
                fullDockerFilePath,
                ctx.createInterpolator(buildConfig.getFilter()));

        } catch (IOException e) {
            // Cant extract base image, so we wont try an auto pull. An error will occur later anyway when
            // building the image, so we are passive here.
            fromImage = null;
        }
        return fromImage;
    }

    public static String extractBaseImage(File dockerFile, Function<String, String> interpolator) throws IOException {
        List<String[]> fromLines = DockerFileBuilder.extractLines(dockerFile, "FROM", interpolator);
        if (!fromLines.isEmpty()) {
            String[] parts = fromLines.get(0);
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return null;
    }

    private void loadImageFromArchive(String imageName, BuildContext ctx, File dockerArchive) throws DockerAccessException {
        long time = System.currentTimeMillis();
        File dockerArchiveAbsolute = ctx.inSourceDir( dockerArchive.getPath());
        docker.loadImage(imageName, dockerArchiveAbsolute);
        log.info("%s: Loaded tarball in %s", dockerArchive, TimeUtil.formatDurationTill(time));
    }

    private File createDockerContextArchive(ImageConfiguration imageConfig, BuildContext ctx) throws IOException {
        long time = System.currentTimeMillis();
        String imageName = imageConfig.getName();
        BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();
        File dockerContextArchive = ctx.createImageContentArchive(imageName, buildConfig, log);
        log.info("%s: Created %s in %s",
                 imageConfig.getDescription(),
                 dockerContextArchive.getName(),
                 TimeUtil.formatDurationTill(time));
        return dockerContextArchive;
    }

    private Optional<String> getOldImageId(String imageName, BuildConfiguration buildConfig) throws DockerAccessException {
        CleanupMode cleanupMode = CleanupMode.parse(buildConfig.getCleanupMode());
        return cleanupMode.isRemove() ?
            Optional.ofNullable(docker.getImageId(imageName)) :
            Optional.empty();
    }

    private String build(ImageConfiguration imageConfig,
                         Map<String, String> buildArgs,
                         File dockerArchive) throws DockerAccessException {
        String imageName = imageConfig.getName();
        BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();
        boolean noCache = checkForNocache(imageConfig);
        BuildOptions opts =
                new BuildOptions(buildConfig.getBuildOptions())
                        .dockerfile(getDockerfileName(buildConfig))
                        .forceRemove(CleanupMode.parse(buildConfig.getCleanupMode()).isRemove())
                        .noCache(noCache)
                        .buildArgs(prepareBuildArgs(buildArgs, buildConfig));
        docker.buildImage(imageName, dockerArchive, opts);
        String newImageId = docker.getImageId(imageName);
        log.info("%s: Built image %s", imageConfig.getDescription(), newImageId);
        return newImageId;
    }

    private void removeOldImage(ImageConfiguration imageConfig, String oldImageId) throws DockerAccessException {
        try {
            docker.removeImage(oldImageId, true);
            log.info("%s: Removed old image %s", imageConfig.getDescription(), oldImageId);
        } catch (DockerAccessException exp) {
            String cleanup = imageConfig.getBuildConfiguration().getCleanupMode();
            if (CleanupMode.parse(cleanup) == CleanupMode.TRY_TO_REMOVE) {
                log.warn("%s: %s (old image)%s", imageConfig.getDescription(), exp.getMessage(),
                         (exp.getCause() != null ? " [" + exp.getCause().getMessage() + "]" : ""));
            } else {
                throw exp;
            }
        }
    }

    private Map<String, String> prepareBuildArgs(Map<String, String> buildArgs, BuildConfiguration buildConfig) {
        Map<String, String> args = new HashMap<>();
        if (buildArgs != null) {
            args.putAll(buildArgs);
        }
        if (buildConfig.getArgs() != null) {
            args.putAll(buildConfig.getArgs());
        }
        return Collections.unmodifiableMap(args);
    }

    private String getDockerfileName(BuildConfiguration buildConfig) {
        if (buildConfig.isDockerFileMode()) {
            return buildConfig.calculateDockerFilePath().getName();
        } else {
            return null;
        }
    }

    private Map<String, String> getBuildArgsFromProperties(BuildContext buildContext, Map<String, String> buildArgs) {
        Map<String, String> ret = new HashMap<>();
        ret.putAll(Optional.ofNullable(buildArgs).orElse(Collections.emptyMap()));
        ret.putAll(getBuildArgsFromProperties(buildContext.getProperties()));
        ret.putAll(getBuildArgsFromProperties(System.getProperties()));
        return Collections.unmodifiableMap(ret);
    }

    private Map<String, String> getBuildArgsFromProperties(Properties properties) {
        String argPrefix = "docker.buildArg.";
        Map<String, String> buildArgs = new HashMap<>();
        if (properties == null) {
            return buildArgs;
        }
        for (Object keyObj : properties.keySet()) {
            String key = (String) keyObj;
            if (key.startsWith(argPrefix)) {
                String argKey = key.replaceFirst(argPrefix, "");
                String value = properties.getProperty(key);

                if (!isEmpty(value)) {
                    buildArgs.put(argKey, value);
                }
            }
        }
        log.debug("Build args set %s", buildArgs);
        return buildArgs;
    }

    private boolean checkForNocache(ImageConfiguration imageConfig) {
        String nocache = System.getProperty("docker.nocache");
        if (nocache != null) {
            return nocache.length() == 0 || Boolean.valueOf(nocache);
        } else {
            BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();
            return buildConfig.getNoCache() != null ? buildConfig.getNoCache() : false;
        }
    }

    private boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
}
