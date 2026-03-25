package com.mchearty.pts.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A dynamic slab block that perfectly emulates the visual, physical, and behavioral
 * properties of a target terrain block while adding smooth-slab geometry and full
 * waterlogging support.
 *
 * <p>Instances are created via the factory {@link #create(Block, ResourceLocation)} which
 * captures the target block for property copying. The slab inherits explosion resistance,
 * friction, sound, destruction progress, plant sustainability, random ticking, falling
 * behavior, and more from the original block. It also implements {@link LiquidBlockContainer}
 * and {@link BucketPickup} for seamless water interaction.
 */
public class PtsTerrainSlabBlock extends SlabBlock implements LiquidBlockContainer, BucketPickup {
  private static final Optional<SoundEvent> PICKUP_SOUND = Optional.of(SoundEvents.BUCKET_FILL);

  /**
   * Thread-local used only during block construction to pass the target block to
   * {@link #createBlockStateDefinition(StateDefinition.Builder)} without polluting
   * the constructor signature.
   */
  private static final ThreadLocal<Block> CURRENT_TARGET = new ThreadLocal<>();

  private final ResourceLocation targetId;
  private Block cachedTarget;

  /**
   * Factory method that creates a new slab instance for the given target block.
   *
   * @param target the original terrain block to emulate
   * @param id the registry name that will be used for the slab
   * @return a fully configured {@link PtsTerrainSlabBlock}
   */
  public static PtsTerrainSlabBlock create(Block target, ResourceLocation id) {
    CURRENT_TARGET.set(target);
    try {
      Properties props = Properties.ofFullCopy(target);
      return new PtsTerrainSlabBlock(props, id, target);
    } finally {
      CURRENT_TARGET.remove();
    }
  }

  private PtsTerrainSlabBlock(Properties properties, ResourceLocation targetId, Block target) {
    super(properties);
    this.targetId = targetId;
    this.cachedTarget = target;

    BlockState baseState = this.stateDefinition.any()
        .setValue(TYPE, SlabType.BOTTOM)
        .setValue(WATERLOGGED, false);

    for (Property<?> prop : target.defaultBlockState().getProperties()) {
      if (baseState.hasProperty(prop) && prop != TYPE && prop != WATERLOGGED) {
        baseState = applyProperty(baseState, prop, target.defaultBlockState().getValue(prop));
      }
    }
    this.registerDefaultState(baseState);
  }

  @SuppressWarnings("unchecked")
  private <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> prop, Comparable<?> value) {
    return state.setValue(prop, (T) value);
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    super.createBlockStateDefinition(builder);
    Block target = CURRENT_TARGET.get();
    if (target != null) {
      for (Property<?> prop : target.getStateDefinition().getProperties()) {
        if (prop != BlockStateProperties.WATERLOGGED && prop != BlockStateProperties.SLAB_TYPE) {
          try {
            builder.add(prop);
          } catch (IllegalArgumentException ignored) {}
        }
      }
    }
  }

  /**
   * Returns the original block that this slab is emulating.
   *
   * @return the target block (never {@code null})
   */
  public Block getTargetBlock() {
    if (cachedTarget == null) cachedTarget = BuiltInRegistries.BLOCK.get(targetId);
    return cachedTarget == null ? Blocks.AIR : cachedTarget;
  }

  @Override
  public MutableComponent getName() {
    return getTargetBlock().getName();
  }

  @Override
  public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
    return new ItemStack(getTargetBlock());
  }

  @Override
  public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.getExplosionResistance(target.defaultBlockState(), level, pos, explosion);
    return super.getExplosionResistance(state, level, pos, explosion);
  }

  @Override
  public float getFriction(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.getFriction(target.defaultBlockState(), level, pos, entity);
    return super.getFriction(state, level, pos, entity);
  }

  @Override
  public float getSpeedFactor() {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.getSpeedFactor();
    return super.getSpeedFactor();
  }

  @Override
  public float getJumpFactor() {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.getJumpFactor();
    return super.getJumpFactor();
  }

  @Override
  public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.getSoundType(target.defaultBlockState(), level, pos, entity);
    return super.getSoundType(state, level, pos, entity);
  }

  @Override
  @SuppressWarnings("deprecation")
  public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.defaultBlockState().getDestroyProgress(player, level, pos);
    return super.getDestroyProgress(state, player, level, pos);
  }

  @Override
  public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos pos, Direction facing, BlockState plant) {
    if (state.getValue(TYPE) == SlabType.BOTTOM) return TriState.FALSE;
    if (plant.is(BlockTags.SAPLINGS)) return TriState.FALSE;
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.defaultBlockState().canSustainPlant(level, pos, facing, plant);
    return super.canSustainPlant(state, level, pos, facing, plant);
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isRandomlyTicking(BlockState state) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) return target.defaultBlockState().isRandomlyTicking();
    return super.isRandomlyTicking(state);
  }

  @Override
  @SuppressWarnings("deprecation")
  public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    Block target = getTargetBlock();
    if (target != Blocks.AIR) target.defaultBlockState().randomTick(level, pos, random);
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
    return context instanceof DirectionalPlaceContext;
  }

  @Override
  @SuppressWarnings("deprecation")
  public FluidState getFluidState(BlockState state) {
    if (state.getValue(WATERLOGGED)) return Fluids.WATER.getSource(false);
    return super.getFluidState(state);
  }

  @Override
  public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
    if (state.getValue(TYPE) == SlabType.DOUBLE) return false;
    if (state.getValue(WATERLOGGED)) return false;
    return fluid == Fluids.WATER;
  }

  @Override
  public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
    if (state.getValue(TYPE) == SlabType.DOUBLE) return false;
    if (state.getValue(WATERLOGGED)) return false;

    if (fluidState.getType() == Fluids.WATER) {
      if (!level.isClientSide()) {
        level.setBlock(pos, state.setValue(WATERLOGGED, true), 3);
        level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
      }
      return true;
    }
    return false;
  }

  @Override
  public ItemStack pickupBlock(@Nullable Player player, LevelAccessor level, BlockPos pos, BlockState state) {
    if (state.getValue(WATERLOGGED)) {
      level.setBlock(pos, state.setValue(WATERLOGGED, false), 3);
      if (!state.canSurvive(level, pos)) level.destroyBlock(pos, true);
      return new ItemStack(Items.WATER_BUCKET);
    }
    return ItemStack.EMPTY;
  }

  @Override
  public Optional<SoundEvent> getPickupSound() {
    return PICKUP_SOUND;
  }

  @Override
  @SuppressWarnings("deprecation")
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    Block target = getTargetBlock();
    BlockState targetState = target.getStateForPlacement(context);
    if (targetState == null) targetState = target.defaultBlockState();

    BlockState state = this.defaultBlockState()
        .setValue(TYPE, SlabType.BOTTOM)
        .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);

    for (Property<?> prop : targetState.getProperties()) {
      if (state.hasProperty(prop) && prop != TYPE && prop != WATERLOGGED) {
        state = applyProperty(state, prop, targetState.getValue(prop));
      }
    }

    BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
    if (existing.is(this)) {
      return existing.setValue(TYPE, SlabType.DOUBLE).setValue(WATERLOGGED, false);
    }

    return state;
  }

  @Override
  @SuppressWarnings("deprecation")
  protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
    if (getTargetBlock() instanceof Fallable) level.scheduleTick(pos, this, 2);
  }

  @Override
  @SuppressWarnings("deprecation")
  protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
    if (state.getValue(WATERLOGGED)) {
      level.scheduleTick(currentPos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
    }
    if (getTargetBlock() instanceof Fallable) {
      level.scheduleTick(currentPos, this, 2);
    }
    return super.updateShape(state, facing, facingState, level, currentPos, facingPos);
  }

  @Override
  @SuppressWarnings("deprecation")
  protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    if (getTargetBlock() instanceof Fallable) {
      if (FallingBlock.isFree(level.getBlockState(pos.below())) && pos.getY() >= level.getMinBuildHeight()) {
        BlockState fallingState = state;
        if (state.getValue(TYPE) == SlabType.TOP) {
          fallingState = state.setValue(TYPE, SlabType.BOTTOM);
        }
        FallingBlockEntity.fall(level, pos, fallingState);
      }
    }
  }
}