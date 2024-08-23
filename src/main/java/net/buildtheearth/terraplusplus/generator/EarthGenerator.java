package net.buildtheearth.terraplusplus.generator;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubeGeneratorsRegistry;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.CubePopulatorEvent;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.ICubicStructureGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.event.InitCubicStructureGeneratorEvent;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.cubicgen.BasicCubeGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.BiomeBlockReplacerConfig;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.CubicBiome;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.IBiomeBlockReplacer;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.IBiomeBlockReplacerProvider;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.OceanWaterReplacer;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.TerrainShapeReplacer;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.CubicCaveGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.CubicRavineGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.feature.CubicStrongholdGenerator;
import lombok.NonNull;
import net.buildtheearth.terraplusplus.TerraConstants;
import net.buildtheearth.terraplusplus.TerraMod;
import net.buildtheearth.terraplusplus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraplusplus.generator.populate.IEarthPopulator;
import net.buildtheearth.terraplusplus.projection.GeographicProjection;
import net.buildtheearth.terraplusplus.projection.OutOfProjectionBoundsException;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.InitMapGenEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.*;

public class EarthGenerator extends BasicCubeGenerator {
    public static final int WATER_DEPTH_OFFSET = 1;

    static {
        ModContainer cubicchunks = Loader.instance().getIndexedModList().get(CubicChunks.MODID);
        String asyncVersion = "1.12.2-0.0.1175.0"; // The version at which async terrain gen was added
        if (cubicchunks != null && asyncVersion.compareTo(TerraConstants.CC_VERSION) <= 0) {
            // Async terrain is supported on this version! Register async generation callbacks
            CubeGeneratorsRegistry.registerColumnAsyncLoadingCallback((world, data) -> asyncCallback(world, data.getPos()));
            CubeGeneratorsRegistry.registerCubeAsyncLoadingCallback((world, data) -> asyncCallback(world, data.getPos().chunkPos()));
        } else {
            // We're on an older version of CC that doesn't support async terrain
            TerraMod.LOGGER.error("Async terrain not available!");
            TerraMod.LOGGER.error("Consider updating to the latest version of Cubic Chunks for maximum performance.");

            try {
                MinecraftForge.EVENT_BUS.register(new Object() {
                    @SubscribeEvent
                    public void onPlayerLogIn(PlayerEvent.PlayerLoggedInEvent event) {
                        event.player.sendMessage(new TextComponentString(
                                "\u00A7c\u00A7lTerra++ is unable to use async terrain!\n"
                                        + "\u00A7c\u00A7lThis will cause significant performance issues.\n"
                                        + "\u00A7c\u00A7lUpdate Cubic Chunks to version 1.12.2-0.0.1175.0 or newer to remove this message."
                        ));
                    }
                });
            } catch (Throwable ignored) {
                // This only happens if launching the debug terrain preview without a Minecraft context
            }
        }
    }

    private static void asyncCallback(World world, ChunkPos pos) {
        ICubicWorldServer cubicWorld;
        if (world instanceof ICubicWorld && (cubicWorld = (ICubicWorldServer) world).isCubicWorld()) { // Ignore vanilla worlds
            ICubeGenerator cubeGenerator = cubicWorld.getCubeGenerator();
            if (cubeGenerator instanceof EarthGenerator) {
                // Prefetch terrain data
                try {
                    ((EarthGenerator) cubeGenerator).cache.getUnchecked(pos);
                } catch (Exception e) {
                    // Catch any unexpected exceptions during async cube/column loading
                    TerraMod.LOGGER.error("Async exception while prefetching data for " + pos, e);
                }
            }
        }
    }

    public static boolean isNullIsland(int chunkX, int chunkZ) {
        return max(chunkX ^ (chunkX >> 31), chunkZ ^ (chunkZ >> 31)) < 3;
    }

    public final EarthGeneratorSettings settings;
    public final BiomeProvider biomes;
    public final GeographicProjection projection;
    private final CustomGeneratorSettings cubiccfg;

    public final IBiomeBlockReplacer[][] biomeBlockReplacers;

    private final List<ICubicStructureGenerator> structureGenerators = new ArrayList<>();

    private final IEarthPopulator[] populators;

    public final GeneratorDatasets datasets;

    public final LoadingCache<ChunkPos, CompletableFuture<CachedChunkData>> cache;

    public EarthGenerator(World world) {
        super(world);

        this.settings = EarthGeneratorSettings.parse(world.getWorldInfo().getGeneratorOptions());
        this.cubiccfg = this.settings.customCubic();
        this.projection = this.settings.projection();

        this.biomes = world.getBiomeProvider();

        this.datasets = this.settings.datasets();
        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(5L, TimeUnit.MINUTES)
                .softValues()
                .build(new ChunkDataLoader(this.settings));

        // Structures
        if (this.cubiccfg.caves) {
            InitCubicStructureGeneratorEvent caveEvent = new InitCubicStructureGeneratorEvent(InitMapGenEvent.EventType.CAVE, new CubicCaveGenerator());
            MinecraftForge.TERRAIN_GEN_BUS.post(caveEvent);
            this.structureGenerators.add(caveEvent.getNewGen());
        }
        if (this.cubiccfg.ravines) {
            InitCubicStructureGeneratorEvent ravineEvent = new InitCubicStructureGeneratorEvent(InitMapGenEvent.EventType.RAVINE, new CubicRavineGenerator(this.cubiccfg));
            MinecraftForge.TERRAIN_GEN_BUS.post(ravineEvent);
            this.structureGenerators.add(ravineEvent.getNewGen());
        }
        if (this.cubiccfg.strongholds) {
            InitCubicStructureGeneratorEvent strongholdsEvent = new InitCubicStructureGeneratorEvent(InitMapGenEvent.EventType.STRONGHOLD, new CubicStrongholdGenerator(this.cubiccfg));
            MinecraftForge.TERRAIN_GEN_BUS.post(strongholdsEvent);
            this.structureGenerators.add(strongholdsEvent.getNewGen());
        }

        this.populators = EarthGeneratorPipelines.populators(this.settings);

        BiomeBlockReplacerConfig conf = this.cubiccfg.createBiomeBlockReplacerConfig();
        Map<Biome, List<IBiomeBlockReplacer>> biomeBlockReplacers = new IdentityHashMap<>();
        for (Biome biome : ForgeRegistries.BIOMES) {
            CubicBiome cubicBiome = CubicBiome.getCubic(biome);
            Iterable<IBiomeBlockReplacerProvider> providers = cubicBiome.getReplacerProviders();
            List<IBiomeBlockReplacer> replacers = new ArrayList<>();
            for (IBiomeBlockReplacerProvider prov : providers) {
                replacers.add(prov.create(world, cubicBiome, conf));
            }

            // Remove these replacers because they're redundant
            replacers.removeIf(replacer -> replacer instanceof TerrainShapeReplacer || replacer instanceof OceanWaterReplacer);

            biomeBlockReplacers.put(biome, replacers);
        }
        this.biomeBlockReplacers = biomeBlockReplacers.values().toArray(new IBiomeBlockReplacer[0][]);
    }

    @Override
    public void populate(ICube cube) {
        CubePos pos = cube.getCoords();

        // Call the pre-populator event
        MinecraftForge.EVENT_BUS.post(new CubePopulatorEvent.Pre(cube));

        // Pre-populate terrain with cached data
        CompletableFuture<CachedChunkData> cache = this.cache.getUnchecked(pos.chunkPos());
        if (!cache.isDone()) {
            // If we're about to generate terrain synchronously,
            // we need to ensure that it's available
            cache.join();
        }
        // Fetch the data, generating synchronously if necessary
        CachedChunkData data = cache.join();

        this.replaceBlocks(data, cube);

        // Generate structures
        Random rand = this.rand(pos.getX(), pos.getY(), pos.getZ());
        for (ICubicStructureGenerator structure : this.structureGenerators) {
            structure.generate(world, this, cube, pos, rand);
        }

        // Populate biome-specific terrain features
        for (IEarthPopulator pop : this.populators) {
            pop.populate(cube, data, this);
        }

        // Call the post-populator event
        MinecraftForge.EVENT_BUS.post(new CubePopulatorEvent.Post(cube));
    }

    @Override
    public void recreateStructures(ICube cube) {
        for (ICubicStructureGenerator structure : this.structureGenerators) {
            structure.recreateStructures(world, cube);
        }
    }

    @Override
    public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ) {
        CompletableFuture<CachedChunkData> cache = this.cache.getUnchecked(new ChunkPos(cubeX, cubeZ));

        // Return a blank cube if this is Null Island
        if (isNullIsland(cubeX, cubeZ)) {
            return CubePrimer.EMPTY;
        }

        if (!cache.isDone()) {
            // If the terrain data isn't available yet,
            // we need to generate the cube synchronously
            cache.join();
        }

        CachedChunkData data = cache.join();

        CubePrimer primer = new CubePrimer();
        this.replaceBlocks(data, primer, cubeX, cubeY, cubeZ);
        return primer;
    }

    protected void replaceBlocks(@NonNull CachedChunkData data, @NonNull CubePrimer primer, int cubeX, int cubeY, int cubeZ) {
        for (int blockX = 0; blockX < 16; blockX++) {
            for (int blockZ = 0; blockZ < 16; blockZ++) {
                for (int blockY = 0; blockY < 16; blockY++) {
                    int x = Coords.localToBlock(cubeX, blockX);
                    int y = Coords.localToBlock(cubeY, blockY);
                    int z = Coords.localToBlock(cubeZ, blockZ);

                    int blockIndex = Coords.blockToLocal(blockX, blockY, blockZ);
                    IBlockState state = data.getBlockState(x, y, z);

                    primer.setBlockState(blockIndex, state);
                }
            }
        }
    }

    protected void replaceBlocks(@NonNull CachedChunkData data, @NonNull ICube cube) {
        CubePos pos = cube.getCoords();
        int cubeX = pos.getX();
        int cubeY = pos.getY();
        int cubeZ = pos.getZ();

        for (int blockX = 0; blockX < 16; blockX++) {
            for (int blockZ = 0; blockZ < 16; blockZ++) {
                for (int blockY = 0; blockY < 16; blockY++) {
                    int x = Coords.localToBlock(cubeX, blockX);
                    int y = Coords.localToBlock(cubeY, blockY);
                    int z = Coords.localToBlock(cubeZ, blockZ);

                    int blockIndex = Coords.blockToLocal(blockX, blockY, blockZ);
                    IBlockState state = data.getBlockState(x, y, z);

                    cube.setBlockForGeneration(blockIndex, state);
                }
            }
        }
    }

    @Override
    public void populate(ICube cube) {
        CubePos pos = cube.getCoords();

        MinecraftForge.EVENT_BUS.post(new CubePopulatorEvent.Pre(cube));

        CompletableFuture<CachedChunkData> cache = this.cache.getUnchecked(pos.chunkPos());
        if (!cache.isDone()) {
            cache.join();
        }

        CachedChunkData data = cache.join();

        this.replaceBlocks(data, cube);

        Random rand = this.rand(pos.getX(), pos.getY(), pos.getZ());
        for (ICubicStructureGenerator structure : this.structureGenerators) {
            structure.generate(world, this, cube, pos, rand);
        }

        for (IEarthPopulator pop : this.populators) {
            pop.populate(cube, data, this);
        }

        MinecraftForge.EVENT_BUS.post(new CubePopulatorEvent.Post(cube));
    }

    private Random rand(int x, int y, int z) {
        Random rand = new Random();
        rand.setSeed(Coords.cubeToCube(x, y, z));
        return rand;
    }
}
