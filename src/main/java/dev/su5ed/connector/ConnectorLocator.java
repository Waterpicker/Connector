package dev.su5ed.connector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.fart.api.Renamer;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LogMarkers;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModJarMetadata;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileLoadingException;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.INamedMappingFile;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

public class ConnectorLocator extends AbstractModProvider implements IModLocator {
    public static final String FABRIC_MOD_JSON = "fabric.mod.json";
    public static final String CONNECTOR_LANGUAGE = "connector";

    private static final String SUFFIX = ".jar";
    private static final String JIJ_ATTRIBUTE_PREFIX = "Additional-Dependencies-";
    private static final String LANGUAGE_JIJ_DEP = "Language";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Marker FART_MARKER = MarkerFactory.getMarker("FART");
    private static final int CACHE_VERSION = 1;

    private final Path modFolder = FMLPaths.MODSDIR.get();
    private final Path selfPath;
    private final Attributes attributes;

    public ConnectorLocator() {
        URL jarLocation = getClass().getProtectionDomain().getCodeSource().getLocation();
        this.selfPath = uncheck(() -> Path.of(jarLocation.toURI()));
        Manifest manifest = new Manifest();
        Path manifestPath = this.selfPath.resolve("META-INF/MANIFEST.MF");
        uncheck(() -> manifest.read(Files.newInputStream(manifestPath)));
        this.attributes = manifest.getMainAttributes();
    }

    @Override
    public List<IModLocator.ModFileOrException> scanMods() {
        LOGGER.debug(LogMarkers.SCAN, "Scanning mods dir {} for mods", this.modFolder);
        List<Path> excluded = ModDirTransformerDiscoverer.allExcluded();

        Stream<IModLocator.ModFileOrException> fabricMods = uncheck(() -> Files.list(this.modFolder))
            .filter(p -> !excluded.contains(p) && StringUtils.toLowerCase(p.getFileName().toString()).endsWith(SUFFIX))
            .sorted(Comparator.comparing(path -> StringUtils.toLowerCase(path.getFileName().toString())))
            .map(this::createConnectorMod);
        Stream<IModLocator.ModFileOrException> additionalDeps = getAdditionalDependencies()
            .map(this::createMod);

        return Stream.concat(fabricMods, additionalDeps).toList();
    }

    protected IModLocator.ModFileOrException createConnectorMod(Path path) {
        SecureJar secureJar = SecureJar.from(path);
        if (secureJar.moduleDataProvider().findFile(FABRIC_MOD_JSON).isPresent()) {
            LOGGER.debug(LogMarkers.SCAN, "Found {} mod: {}", FABRIC_MOD_JSON, path);

            Path remapped = uncheck(() -> remapJar(path.toFile()));
            // TODO Method Handle
            ModJarMetadata mjm = uncheck(() -> {
                Constructor<ModJarMetadata> cst = ModJarMetadata.class.getDeclaredConstructor();
                cst.setAccessible(true);
                return cst.newInstance();
            });
            SecureJar modJar = SecureJar.from(
                Manifest::new,
                jar -> jar.moduleDataProvider().findFile(FABRIC_MOD_JSON).isPresent() ? mjm : JarMetadata.from(jar, remapped),
                (root, p) -> true,
                remapped
            );
            IModFile mod = new ModFile(modJar, this, ConnectorModMetadataParser::fabricModJsonParser);
            mjm.setModFile(mod);
            return new IModLocator.ModFileOrException(mod, null);
        } else {
            return new IModLocator.ModFileOrException(null, new ModFileLoadingException("Invalid mod file found " + path));
        }
    }

    private Path remapJar(File input) throws IOException {
        Path remappedDir = this.modFolder.resolve("connector");
        Files.createDirectories(remappedDir);
        String suffix = "_mapped_official_1.19.4";

        String name = input.getName().split("\\.")[0];
        Path output = remappedDir.resolve(name + suffix + ".jar");
        Path inputCache = remappedDir.resolve(name + suffix + ".jar.input");

        String checksum;
        try (InputStream is = new FileInputStream(input)) {
            checksum = CACHE_VERSION + "," + DigestUtils.sha256Hex(is);
        }

        if (Files.exists(inputCache) && Files.exists(output)) {
            String cached = Files.readString(inputCache);
            if (cached.equals(checksum)) {
                return output;
            }
        }

        // TODO Cache mapping
        Path mappingsFile = remappedDir.resolve("mappings.tsrg");
        if (Files.notExists(mappingsFile)) {
            Path mappingsResource = this.selfPath.resolve(mappingsFile.getFileName().toString());
            Files.copy(mappingsResource, mappingsFile);
        }
        
        Set<String> configs = new HashSet<>();
        Set<String> refmaps = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(input)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(zipFile.getEntry(FABRIC_MOD_JSON))))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray mixins = json.getAsJsonArray("mixins");
                for (JsonElement element : mixins) {
                    configs.add(element.getAsString());
                }
            }

            zipFile.stream()
                .filter(entry -> configs.contains(entry.getName()))
                .forEach(entry -> {
                    try (Reader reader = new InputStreamReader(zipFile.getInputStream(entry))) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        if (json.has("refmap")) {
                            String refmap = json.get("refmap").getAsString();
                            refmaps.add(refmap);
                        }
                    } catch (IOException e) {
                        LOGGER.error("Error reading mixin config entry {} in file {}", entry.getName(), input.getAbsolutePath());
                        throw new UncheckedIOException(e);
                    }
                });
        }

        INamedMappingFile namedMapping = INamedMappingFile.load(mappingsFile.toFile());
        IMappingFile yarnToOfficial = namedMapping.getMap("yarn", "official");
        IMappingFile intermediaryToSrg = namedMapping.getMap("intermediary", "srg");
        SrgRemappingReferenceMapper remapper = new SrgRemappingReferenceMapper(intermediaryToSrg);

        try (Renamer renamer = Renamer.builder()
            .add(Transformer.renamerFactory(yarnToOfficial, false))
            .add(new RefmapTransformer(configs, refmaps, remapper))
            .add(new AccessWidenerTransformer(namedMapping))
            .logger(s -> LOGGER.trace(FART_MARKER, s))
            .debug(s -> LOGGER.trace(FART_MARKER, s))
            .build())
        {
            renamer.run(input, output.toFile());
            Files.writeString(inputCache, checksum);
        }

        return output;
    }

    private Stream<Path> getAdditionalDependencies() {
        try {
            Path languageJij = getJarInJar(LANGUAGE_JIJ_DEP);
            return Stream.of(languageJij);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path getJarInJar(String name) {
        String depName = this.attributes.getValue(JIJ_ATTRIBUTE_PREFIX + name);
        if (depName == null) {
            throw new IllegalArgumentException("Required " + name + " embedded jar not found");
        }
        return this.selfPath.resolve(depName);
    }

    @Override
    public String name() {
        return "connector";
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {}

    @Override
    public void initArguments(Map<String, ?> arguments) {}
}
