package dev.su5ed.sinytra.connector.transformer.jar;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import dev.su5ed.sinytra.adapter.patch.LVTOffsets;
import dev.su5ed.sinytra.adapter.patch.api.GlobalReferenceMapper;
import dev.su5ed.sinytra.adapter.patch.api.Patch;
import dev.su5ed.sinytra.adapter.patch.api.PatchEnvironment;
import dev.su5ed.sinytra.adapter.patch.serialization.PatchSerialization;
import dev.su5ed.sinytra.adapter.patch.util.provider.ClassLookup;
import dev.su5ed.sinytra.adapter.patch.util.provider.ZipClassLookup;
import dev.su5ed.sinytra.connector.locator.EmbeddedDependencies;
import dev.su5ed.sinytra.connector.transformer.AccessWidenerTransformer;
import dev.su5ed.sinytra.connector.transformer.AccessorRedirectTransformer;
import dev.su5ed.sinytra.connector.transformer.FieldToMethodTransformer;
import dev.su5ed.sinytra.connector.transformer.JarSignatureStripper;
import dev.su5ed.sinytra.connector.transformer.MixinPatchTransformer;
import dev.su5ed.sinytra.connector.transformer.ModMetadataGenerator;
import dev.su5ed.sinytra.connector.transformer.OptimizedRenamingTransformer;
import dev.su5ed.sinytra.connector.transformer.RefmapRemapper;
import dev.su5ed.sinytra.connector.transformer.SrgRemappingReferenceMapper;
import dev.su5ed.sinytra.connector.transformer.patch.ClassAnalysingTransformer;
import dev.su5ed.sinytra.connector.transformer.patch.ClassNodeTransformer;
import dev.su5ed.sinytra.connector.transformer.patch.ConnectorRefmapHolder;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.MappingResolverImpl;
import net.minecraftforge.coremod.api.ASMAPI;
import net.minecraftforge.fart.api.ClassProvider;
import net.minecraftforge.fart.api.Renamer;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.targets.CommonLaunchHandler;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.srgutils.IMappingFile;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;
import static dev.su5ed.sinytra.connector.transformer.jar.JarTransformer.*;

public class JarTransformInstance {
    private static final String FABRIC_MAPPING_NAMESPACE = "Fabric-Mapping-Namespace";
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SrgRemappingReferenceMapper remapper;
    private final List<? extends Patch> adapterPatches;
    private final LVTOffsets lvtOffsetsData;
    private final BytecodeFixerUpperFrontend bfu;
    private final Transformer remappingTransformer;
    private final ClassLookup cleanClassLookup;
    private final List<Path> libs;

    public JarTransformInstance(ClassProvider classProvider, Iterable<IModFile> loadedMods, List<Path> libs) {
        MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
        resolver.getMap(OBF_NAMESPACE, SOURCE_NAMESPACE);
        resolver.getMap(SOURCE_NAMESPACE, OBF_NAMESPACE);
        this.remapper = new SrgRemappingReferenceMapper(resolver.getCurrentMap(SOURCE_NAMESPACE));

        Path patchDataPath = EmbeddedDependencies.getAdapterData(EmbeddedDependencies.ADAPTER_PATCH_DATA);
        try (Reader reader = Files.newBufferedReader(patchDataPath)) {
            JsonElement json = GSON.fromJson(reader, JsonElement.class);
            GlobalReferenceMapper.setReferenceMapper(str -> str == null ? null : str.startsWith("m_") ? ASMAPI.mapMethod(str) : ASMAPI.mapField(str));
            this.adapterPatches = PatchSerialization.deserialize(json, JsonOps.INSTANCE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Path offsetDataPath = EmbeddedDependencies.getAdapterData(EmbeddedDependencies.ADAPTER_LVT_OFFSETS);
        try (Reader reader = Files.newBufferedReader(offsetDataPath)) {
            JsonElement json = GSON.fromJson(reader, JsonElement.class);
            this.lvtOffsetsData = LVTOffsets.fromJson(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        this.bfu = new BytecodeFixerUpperFrontend();
        this.remappingTransformer = OptimizedRenamingTransformer.create(classProvider, s -> {}, FabricLoaderImpl.INSTANCE.getMappingResolver().getCurrentMap(SOURCE_NAMESPACE), IntermediateMapping.get(SOURCE_NAMESPACE));
        this.cleanClassLookup = createCleanClassLookup();
        this.libs = libs;

        MixinPatchTransformer.completeSetup(loadedMods);
    }

    public BytecodeFixerUpperFrontend getBfu() {
        return bfu;
    }

    public void transformJar(File input, Path output, FabricModFileMetadata metadata) throws IOException {
        Stopwatch stopwatch = Stopwatch.createStarted();

        if (metadata.generated()) {
            processGeneratedJar(input, output, metadata, stopwatch);
            return;
        }

        String jarMapping = metadata.manifestAttributes().getValue(FABRIC_MAPPING_NAMESPACE);
        if (jarMapping != null && !jarMapping.equals(SOURCE_NAMESPACE)) {
            LOGGER.error("Found transformable jar with unsupported mapping {}, currently only {} is supported", jarMapping, SOURCE_NAMESPACE);
        }

        MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
        RefmapRemapper.RefmapFiles refmap = RefmapRemapper.processRefmaps(input.toPath(), metadata.refmaps(), this.remapper, this.libs);
        IMappingFile srgToIntermediary = resolver.getMap(OBF_NAMESPACE, SOURCE_NAMESPACE);
        IMappingFile intermediaryToSrg = resolver.getCurrentMap(SOURCE_NAMESPACE);
        AccessorRedirectTransformer accessorRedirectTransformer = new AccessorRedirectTransformer(srgToIntermediary);

        List<Patch> extraPatches = Stream.concat(this.adapterPatches.stream(), AccessorRedirectTransformer.PATCHES.stream()).toList();
        ConnectorRefmapHolder refmapHolder = new ConnectorRefmapHolder(refmap.merged(), refmap.files());
        PatchEnvironment environment = PatchEnvironment.create(refmapHolder, this.cleanClassLookup, this.bfu.unwrap());
        MixinPatchTransformer patchTransformer = new MixinPatchTransformer(this.lvtOffsetsData, metadata.mixinPackages(), environment, extraPatches);
        RefmapRemapper refmapRemapper = new RefmapRemapper(metadata.visibleMixinConfigs(), refmap.files());
        Renamer.Builder builder = Renamer.builder()
            .add(new JarSignatureStripper())
            .add(new ClassNodeTransformer(
                new FieldToMethodTransformer(metadata.modMetadata().getAccessWidener(), srgToIntermediary),
                accessorRedirectTransformer,
                new ClassAnalysingTransformer(intermediaryToSrg, IntermediateMapping.get(SOURCE_NAMESPACE))
            ))
            .add(this.remappingTransformer)
            .add(patchTransformer)
            .add(refmapRemapper)
            .add(new ModMetadataGenerator(metadata.modMetadata().getId()))
            .logger(s -> LOGGER.trace(TRANSFORM_MARKER, s))
            .debug(s -> LOGGER.trace(TRANSFORM_MARKER, s));
        if (!metadata.containsAT()) {
            builder.add(new AccessWidenerTransformer(metadata.modMetadata().getAccessWidener(), resolver, IntermediateMapping.get(SOURCE_NAMESPACE)));
        }
        try (Renamer renamer = builder.build()) {
            accessorRedirectTransformer.analyze(input, metadata.mixinPackages(), environment);

            renamer.run(input, output.toFile());

            try (FileSystem zipFile = FileSystems.newFileSystem(output)) {
                patchTransformer.finalize(zipFile.getPath("/"), metadata.mixinConfigs(), refmap.files(), refmapHolder.getDirtyRefmaps());
            }
        } catch (Throwable t) {
            LOGGER.error("Encountered error while transforming jar file " + input.getAbsolutePath(), t);
            throw t;
        }

        stopwatch.stop();
        LOGGER.debug(TRANSFORM_MARKER, "Jar {} transformed in {} ms", input.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    private static void processGeneratedJar(File input, Path output, FabricModFileMetadata metadata, Stopwatch stopwatch) throws IOException {
        Files.copy(input.toPath(), output);
        try (FileSystem fs = FileSystems.newFileSystem(output)) {
            Path packMetadata = fs.getPath(ModMetadataGenerator.RESOURCE);
            if (Files.notExists(packMetadata)) {
                byte[] data = ModMetadataGenerator.generatePackMetadataFile(metadata.modMetadata().getId());
                Files.write(packMetadata, data);
            }
        }
        stopwatch.stop();
        LOGGER.debug(TRANSFORM_MARKER, "Skipping transformation of jar {} after {} ms as it contains generated metadata, assuming it's a java library", input.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    private static ClassLookup createCleanClassLookup() {
        if (FMLEnvironment.production) {
            CommonLaunchHandler.LocatedPaths paths = FMLLoader.getLaunchHandler().getMinecraftPaths();
            Path cleanPath = paths.minecraftPaths().stream()
                .filter(path -> path.getFileName().toString().contains("-srg"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot find clean artifact path"));
            ZipFile zipFile = uncheck(() -> new ZipFile(cleanPath.toFile()));
            return new ZipClassLookup(zipFile);
        }
        else {
            // Search for system property
            Path cleanPath = Optional.ofNullable(System.getProperty("connector.clean.path"))
                .map(Path::of)
                // If not found, attempt to guess the path
                .or(() -> Optional.ofNullable(System.getProperty("user.home"))
                    .map(str -> {
                        String mcpVersion = FMLLoader.versionInfo().mcAndMCPVersion();
                        return Path.of(str).resolve(".gradle/caches/forge_gradle/mcp_repo/net/minecraft/joined/%s/joined-%s-srg.jar".formatted(mcpVersion, mcpVersion));
                    }))
                .filter(Files::exists)
                .orElseThrow(() -> new RuntimeException("Could not determine clean minecraft artifact path"));
            ClassProvider obfProvider = ClassProvider.fromPaths(cleanPath);
            IMappingFile mapping = FabricLoaderImpl.INSTANCE.getMappingResolver().getCurrentMap(OBF_NAMESPACE);
            return new RenamingClassLookup(obfProvider, mapping);
        }
    }
}
