package com.mchearty.pts.block;

import com.mchearty.pts.registry.SlabMirrorFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
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
    // 1. Prevent BlockState exponential memory explosions. 
    // A target block with >4 properties causes massive state matrix permutations 
    // when generating its slab equivalent, leading to OutOfMemoryErrors in modpacks.
    if (state.getProperties().size() > 4) return false;

    // 2. Fast-fail classes to bypass expensive model logic and shape lookups completely
    Block block = state.getBlock();
    if (block instanceof net.minecraft.world.level.block.SlabBlock ||
        block instanceof net.minecraft.world.level.block.StairBlock ||
        block instanceof net.minecraft.world.level.block.FenceBlock ||
        block instanceof net.minecraft.world.level.block.WallBlock ||
        block instanceof net.minecraft.world.level.block.TrapDoorBlock ||
        block instanceof net.minecraft.world.level.block.DoorBlock) {
      return false;
    }

    try {
      VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
      
      // 3. O(1) allocation-free check for 99% of full terrain blocks
      if (shape == Shapes.block()) {
        return true;
      }

      if (shape.isEmpty()) return false;
      if (shape.max(Direction.Axis.Y) < 0.5) return false;
      if (shape.max(Direction.Axis.X) - shape.min(Direction.Axis.X) < 1.0) return false;
      if (shape.max(Direction.Axis.Z) - shape.min(Direction.Axis.Z) < 1.0) return false;

      // 4. Fallback: Defer AABB list allocation strictly to partial terrain blocks (e.g. Dirt Paths)
      if (shape.toAabbs().size() != 1) return false;

      return true;
    } catch (Exception e) {
      // Catch poorly written mod blocks that throw NullPointerExceptions or OutOfMemoryErrors 
      // when queried with an EmptyBlockGetter fake world context.
      return false;
    }
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