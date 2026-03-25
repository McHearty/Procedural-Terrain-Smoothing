package com.mchearty.pts.worldgen;

import com.mchearty.pts.block.ModBlocks;
import com.mchearty.pts.block.PtsTerrainSlabBlock;
import com.mchearty.pts.config.PtsConfigService;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

import java.util.Objects;

/**
 * World-generation feature that converts flat terrain faces into procedurally
 * smoothed slabs according to the PTS configuration.
 *
 * <p>Scans every column in a chunk, detects air or water source blocks directly
 * above or below a smoothing-target block, and replaces them with the appropriate
 * bottom or top slab. The feature respects the {@code EDGE_CONVERSION_PERCENT}
 * probability and optionally generates corner slabs when {@code GENERATE_CORNERS}
 * is enabled. All property copying from the original block is performed safely
 * to preserve grass/snow/age/etc. states.
 */
public class TerrainSmoothingFeature extends Feature<NoneFeatureConfiguration> {

  /**
   * Constructs the feature with the required codec.
   *
   * @param codec codec for {@link NoneFeatureConfiguration}
   */
  public TerrainSmoothingFeature(Codec<NoneFeatureConfiguration> codec) {
    super(codec);
  }

  @Override
  public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
    WorldGenLevel level = context.level();

    ChunkPos chunkPos = new ChunkPos(context.origin());
    int startX = chunkPos.getMinBlockX();
    int startZ = chunkPos.getMinBlockZ();
    int minBuildHeight = level.getMinBuildHeight() + 1;

    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, startX + x, startZ + z);

        for (int y = surfaceY; y >= minBuildHeight; y--) {
          pos.set(startX + x, y, startZ + z);
          BlockState currentState = level.getBlockState(pos);

          boolean isAir = currentState.isAir();
          boolean isFluidSource = currentState.getFluidState().isSource();

          if (!isAir && !isFluidSource) continue;

          BlockState belowState = level.getBlockState(pos.below());
          BlockState aboveState = level.getBlockState(pos.above());

          if (!ModBlocks.isSmoothingTarget(belowState.getBlock()) && !ModBlocks.isSmoothingTarget(aboveState.getBlock())) continue;

          boolean isWater = currentState.getFluidState().isSourceOfType(Fluids.WATER);

          if (ModBlocks.isSmoothingTarget(belowState.getBlock())) {
            if (Block.isFaceFull(belowState.getCollisionShape(level, pos.below()), Direction.UP)) {
              BlockPos plateauPos = getPlateauAnchor(level, pos, belowState.getBlock());
              if (plateauPos != null && hasVerticalClearance(level, pos)) {
                if (passesErodeFilter(plateauPos)) {
                  SlabBlock slabBlock = ModBlocks.getSlabFor(belowState.getBlock());
                  if (slabBlock != null) {
                    BlockState newSlab = slabBlock.defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, isWater);

                    newSlab = copyPropertiesSafe(belowState, newSlab);

                    level.setBlock(pos, newSlab, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    continue;
                  }
                }
              }
            }
          }

          if (ModBlocks.isSmoothingTarget(aboveState.getBlock())) {
            BlockPos plateauPos = getPlateauAnchor(level, pos, aboveState.getBlock());
            if (plateauPos != null) {
              if (passesErodeFilter(plateauPos)) {
                SlabBlock slabBlock = ModBlocks.getSlabFor(aboveState.getBlock());
                if (slabBlock != null) {
                  BlockState newSlab = slabBlock.defaultBlockState()
                      .setValue(SlabBlock.TYPE, SlabType.TOP)
                      .setValue(SlabBlock.WATERLOGGED, isWater);

                  newSlab = copyPropertiesSafe(aboveState, newSlab);

                  level.setBlock(pos, newSlab, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
              }
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Copies all non-slab-specific properties from the source block state to the target
   * slab state.
   *
   * @param source the original block state
   * @param target the freshly created slab state
   * @return the target state with copied properties
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private BlockState copyPropertiesSafe(BlockState source, BlockState target) {
    BlockState result = target;
    for (Property<?> prop : source.getProperties()) {
      if (result.hasProperty(prop) && prop != SlabBlock.TYPE && prop != SlabBlock.WATERLOGGED) {
        result = result.setValue((Property) prop, source.getValue(prop));
      }
    }
    return result;
  }

  /**
   * Determines whether the current position should receive a slab based on the
   * configured edge-conversion probability.
   *
   * @param anchor a representative block position used to seed the deterministic hash
   * @return {@code true} if a slab should be placed at this location
   */
  private boolean passesErodeFilter(BlockPos anchor) {
    int hash = Objects.hash(anchor.getX(), anchor.getY(), anchor.getZ());
    return Math.floorMod(hash, 100) < PtsConfigService.EDGE_CONVERSION_PERCENT;
  }

  /**
   * Locates a suitable "plateau anchor" block in the cardinal (and optionally diagonal)
   * directions that can serve as the reference for the smoothing operation.
   *
   * @param level the world
   * @param origin the air/water position being replaced
   * @param target the block type we are smoothing
   * @return a valid anchor position or {@code null} if none was found
   */
  private BlockPos getPlateauAnchor(WorldGenLevel level, BlockPos origin, Block target) {
    int matches = 0;
    BlockPos anchor = null;

    BlockPos north = origin.relative(Direction.NORTH);
    if (level.getBlockState(north).is(target)) { matches++; anchor = north; }

    BlockPos south = origin.relative(Direction.SOUTH);
    if (level.getBlockState(south).is(target)) { matches++; anchor = south; }

    BlockPos east = origin.relative(Direction.EAST);
    if (level.getBlockState(east).is(target)) { matches++; anchor = east; }

    BlockPos west = origin.relative(Direction.WEST);
    if (level.getBlockState(west).is(target)) { matches++; anchor = west; }

    if (matches == 1 || matches == 2) return anchor;

    if (PtsConfigService.GENERATE_CORNERS && matches == 0) {
      BlockPos northEast = north.relative(Direction.EAST);
      if (level.getBlockState(northEast).is(target)) return northEast;

      BlockPos northWest = north.relative(Direction.WEST);
      if (level.getBlockState(northWest).is(target)) return northWest;

      BlockPos southEast = south.relative(Direction.EAST);
      if (level.getBlockState(southEast).is(target)) return southEast;

      BlockPos southWest = south.relative(Direction.WEST);
      if (level.getBlockState(southWest).is(target)) return southWest;
    }
    return null;
  }

  /**
   * Checks that the two blocks directly above the replacement position are either
   * air or a water source, ensuring the slab will not be placed inside solid terrain.
   *
   * @param level the world
   * @param pos the position being replaced
   * @return {@code true} if vertical clearance exists
   */
  private boolean hasVerticalClearance(WorldGenLevel level, BlockPos pos) {
    BlockPos above1Pos = pos.above();
    BlockState above1 = level.getBlockState(above1Pos);
    boolean clear1 = above1.isAir() || above1.getFluidState().isSource();

    BlockPos above2Pos = above1Pos.above();
    BlockState above2 = level.getBlockState(above2Pos);
    boolean clear2 = above2.isAir() || above2.getFluidState().isSource();

    return clear1 && clear2;
  }
}