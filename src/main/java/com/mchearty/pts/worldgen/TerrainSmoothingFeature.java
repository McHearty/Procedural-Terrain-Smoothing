package com.mchearty.pts.worldgen;

import com.mchearty.pts.block.ModBlocks;
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
 * Worldgen feature that procedurally inserts PTS slabs into air or water blocks
 * directly above or below eligible terrain surfaces, creating smooth transitions.
 *
 * <p>Respects configuration for edge-conversion probability and corner generation.
 * Copies all blockstate properties from the mirrored target block.
 */
public class TerrainSmoothingFeature extends Feature<NoneFeatureConfiguration> {

  /**
   * @param codec the feature codec
   */
  public TerrainSmoothingFeature(Codec<NoneFeatureConfiguration> codec) {
    super(codec);
  }

  @Override
  @SuppressWarnings("unchecked")
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

                    for (Property<?> prop : belowState.getProperties()) {
                      if (newSlab.hasProperty(prop) && prop != SlabBlock.TYPE && prop != SlabBlock.WATERLOGGED) {
                        newSlab = newSlab.setValue((Property) prop, belowState.getValue(prop));
                      }
                    }

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

                  for (Property<?> prop : aboveState.getProperties()) {
                    if (newSlab.hasProperty(prop) && prop != SlabBlock.TYPE && prop != SlabBlock.WATERLOGGED) {
                      newSlab = newSlab.setValue((Property) prop, aboveState.getValue(prop));
                    }
                  }

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

  private boolean passesErodeFilter(BlockPos anchor) {
    int hash = Objects.hash(anchor.getX(), anchor.getY(), anchor.getZ());
    return Math.floorMod(hash, 100) < PtsConfigService.EDGE_CONVERSION_PERCENT;
  }

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