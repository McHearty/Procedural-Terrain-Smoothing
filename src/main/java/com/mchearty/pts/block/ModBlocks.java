package com.mchearty.pts.block;

import com.mchearty.pts.registry.SlabMirrorFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Maintains a runtime cache mapping original terrain blocks to their
 * dynamically generated PTS slab counterparts.
 *
 * <p>Populated during {@link FMLCommonSetupEvent} after all blocks have been
 * registered. Provides fast lookup for worldgen and validation logic.
 */
public class ModBlocks {

  /** Cache of target block → generated PTS slab. */
  private static final Map<Block, SlabBlock> TARGET_TO_SLAB = new HashMap<>();

  /**
   * Builds the {@link #TARGET_TO_SLAB} cache after registry events complete.
   *
   * <p>Only blocks that pass {@link #isValidForSmoothing(BlockState)} are cached.
   *
   * @param event the common setup event
   */
  public static void setupCache(FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
      for (PtsTerrainSlabBlock slab : SlabMirrorFactory.PENDING_SLABS.values()) {
        Block target = slab.getTargetBlock();
        if (target != Blocks.AIR && isValidForSmoothing(target.defaultBlockState())) {
          TARGET_TO_SLAB.put(target, slab);
        }
      }
    });
  }

  /**
   * Determines whether a block is eligible for PTS slab mirroring.
   *
   * <p>Rejects blocks with block entities, redstone signals, fluids, unbreakable
   * blocks, or non-cubic collision shapes.
   *
   * @param state the block state to test
   * @return {@code true} if the block may be replaced by a PTS slab
   */
  public static boolean isValidForSmoothing(BlockState state) {
    if (state.hasBlockEntity()) return false;
    if (state.isSignalSource()) return false;
    if (!state.getFluidState().isEmpty()) return false;
    if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) return false;

    VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    if (shape.isEmpty()) return false;
    if (shape.toAabbs().size() != 1) return false;

    if (shape.max(Direction.Axis.Y) < 0.5) return false;
    if (shape.max(Direction.Axis.X) - shape.min(Direction.Axis.X) < 1.0) return false;
    if (shape.max(Direction.Axis.Z) - shape.min(Direction.Axis.Z) < 1.0) return false;

    return true;
  }

  /**
   * Returns whether the supplied block has a corresponding PTS slab.
   *
   * @param block the block to test
   * @return {@code true} if a slab exists for this block
   */
  public static boolean isSmoothingTarget(Block block) {
    return TARGET_TO_SLAB.containsKey(block);
  }

  /**
   * Retrieves the PTS slab registered for the given original block.
   *
   * @param block the original terrain block
   * @return the mirrored slab, or {@code null} if none exists
   */
  public static SlabBlock getSlabFor(Block block) {
    return TARGET_TO_SLAB.get(block);
  }
}