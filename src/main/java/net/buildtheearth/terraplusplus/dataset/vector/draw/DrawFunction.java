package net.buildtheearth.terraplusplus.dataset.vector.draw;

import lombok.NonNull;
import net.buildtheearth.terraplusplus.generator.CachedChunkData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.template.PlacementSettings;
import net.minecraft.world.gen.feature.template.Template;

import java.util.Map;

public class CustomStructureDrawFunction implements DrawFunction {

    private final World world;
    private final Map<String, String> osmTags; // OSM tags associated with the structure
    private final String basePath;

    public CustomStructureDrawFunction(World world, Map<String, String> osmTags, String basePath) {
        this.world = world;
        this.osmTags = osmTags;
        this.basePath = basePath;
    }

    @Override
    public void drawOnto(@NonNull CachedChunkData.Builder data, int x, int z, int weight) {
        // Convert x, z to a BlockPos
        BlockPos pos = new BlockPos(x, 0, z); // Adjust Y coordinate if necessary

        // Loop through OSM tags to check for known structures
        for (Map.Entry<String, String> entry : osmTags.entrySet()) {
            String tagKey = entry.getKey();
            String tagValue = entry.getValue();

            // Check if this tag is associated with a structure
            if (isStructureTag(tagKey, tagValue)) {
                // Construct the path to the NBT file based on the tag
                String structurePath = constructNBTPath(tagKey, tagValue);

                // Load and place the structure
                placeStructure(world, pos, structurePath);
            }
        }
    }

    private boolean isStructureTag(String tagKey, String tagValue) {
        // Define logic to determine if a tag corresponds to a structure
        return tagKey.equals("amenity") || tagKey.equals("leisure") || tagKey.equals("tourism") || tagKey.equals("shop");
    }

    private String constructNBTPath(String tagKey, String tagValue) {
        // Construct a dynamic path to the NBT file based on the tag
        return basePath + "/" + tagKey + "/" + tagValue + ".nbt";
    }

    private void placeStructure(World world, BlockPos pos, String structurePath) {
        // Load the NBT structure from the given path
        ResourceLocation structureLocation = new ResourceLocation("yourmodid", structurePath);
        Template structure = world.getStructureManager().getTemplate(structureLocation);

        if (structure != null) {
            // Place the structure in the world
            structure.placeInWorld(world, pos, new PlacementSettings(), world.rand);
        } else {
            // Handle case where the structure could not be found
            System.out.println("Structure not found at path: " + structurePath);
        }
    }
}
