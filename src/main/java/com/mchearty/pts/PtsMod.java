package com.mchearty.pts;

import com.mchearty.pts.block.ModBlocks;
import com.mchearty.pts.block.PtsTerrainSlabBlock;
import com.mchearty.pts.config.PtsConfigService;
import com.mchearty.pts.datagen.PtsDataGen;
import com.mchearty.pts.pack.PtsDynamicPackEngine;
import com.mchearty.pts.registry.ModFeatures;
import com.mchearty.pts.registry.SlabMirrorFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;

import java.util.Optional;
import java.util.Set;

/**
 * Main entry point for the Procedural Terrain Smoothing (PTS) mod.
 *
 * <p>PTS dynamically evaluates terrain block models and blockstates at runtime via
 * exhaustive JSON introspection, mathematically slicing UVs and geometries to
 * produce perfectly accurate slab variants. All property, tag, and multipart
 * data is cloned transparently. The mod registers during NeoForge's pack
 * evaluation phase with no race conditions.
 *
 * <p>Systems involved:
 * <ul>
 *   <li>Runtime Model Introspection Pipeline</li>
 *   <li>Dynamic Pack Generation</li>
 *   <li>Dynamic Registry Injection</li>
 *   <li>Worldgen Placement</li>
 * </ul>
 */
@Mod(PtsMod.MODID)
public class PtsMod {

  /** The mod ID used for registration and resource locations. */
  public static final String MODID = "pts";

  /**
   * Constructs the mod and wires all event listeners with precise lifecycle ordering.
   *
   * @param modEventBus the NeoForge mod event bus
   */
  public PtsMod(IEventBus modEventBus) {
    PtsConfigService.loadConfig();

    modEventBus.addListener(EventPriority.LOWEST, SlabMirrorFactory::onRegisterBlocks);
    modEventBus.addListener(EventPriority.LOWEST, SlabMirrorFactory::onRegisterItems);
    modEventBus.addListener(this::onPackFinder);
    modEventBus.addListener(ModBlocks::setupCache);
    modEventBus.addListener(PtsDataGen::gatherData);
    modEventBus.addListener(this::enqueueIMC);

    if (FMLEnvironment.dist == Dist.CLIENT) {
      modEventBus.addListener(PtsClient::onBlockColors);
      modEventBus.addListener(PtsClient::onItemColors);
    }

    ModFeatures.FEATURES.register(modEventBus);
  }

  private void enqueueIMC(InterModEnqueueEvent event) {
    try {
      // Attempt to natively push parent block definitions to the Distant Horizons API
      Class<?> api = Class.forName("com.seibel.distanthorizons.api.DistantHorizonsAPI");
      Object manager = api.getMethod("getOverrideManager").invoke(null);
      java.lang.reflect.Method addAlias = manager.getClass().getMethod("addBlockAlias", String.class, String.class);
      
      for (PtsTerrainSlabBlock slab : SlabMirrorFactory.PENDING_SLABS.values()) {
        ResourceLocation targetId = BuiltInRegistries.BLOCK.getKey(slab.getTargetBlock());
        ResourceLocation slabId = BuiltInRegistries.BLOCK.getKey(slab);
        if (slabId != null && targetId != null) {
          addAlias.invoke(manager, slabId.toString(), targetId.toString());
        }
      }
    } catch (Throwable ignored) {
      // Fallback to standard NeoForge IMC dispatch if direct DH API has shifted or is missing
      for (PtsTerrainSlabBlock slab : SlabMirrorFactory.PENDING_SLABS.values()) {
        ResourceLocation targetId = BuiltInRegistries.BLOCK.getKey(slab.getTargetBlock());
        ResourceLocation slabId = BuiltInRegistries.BLOCK.getKey(slab);
        if (slabId != null && targetId != null) {
          net.neoforged.fml.InterModComms.sendTo("distanthorizons", "block_alias", () -> slabId.toString() + "=" + targetId.toString());
        }
      }
    }
  }

  /**
   * Adds the dynamically generated PTS resource pack to the repository during
   * {@link AddPackFindersEvent}.
   *
   * <p>The pack is placed at the top of the pack stack and is valid for both
   * {@code CLIENT_RESOURCES} and {@code SERVER_DATA}.
   *
   * @param event the pack finder event
   */
  private void onPackFinder(AddPackFindersEvent event) {
    if (event.getPackType() == PackType.CLIENT_RESOURCES || event.getPackType() == PackType.SERVER_DATA) {
      Set<ResourceLocation> snapshot = Set.copyOf(SlabMirrorFactory.PENDING_SLABS.keySet());
      PtsDynamicPackEngine.generateOrUpdateRuntimePack(snapshot);

      PackLocationInfo info = new PackLocationInfo(
          "pts_dynamic",
          Component.literal("PTS Dynamic Runtime Assets"),
          PackSource.BUILT_IN,
          Optional.empty()
      );

      Pack pack = Pack.readMetaAndCreate(
          info,
          new PathPackResources.PathResourcesSupplier(PtsDynamicPackEngine.PACK_DIR),
          event.getPackType(),
          new PackSelectionConfig(true, Pack.Position.TOP, false)
      );

      if (pack != null) {
        event.addRepositorySource(consumer -> consumer.accept(pack));
      }
    }
  }

  /**
   * Client-only helper for registering block color handlers that delegate to
   * the original target block's tinting logic.
   */
  public static class PtsClient {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyPropertyUnsafe(BlockState state, Property prop, Comparable value) {
      return state.setValue(prop, value);
    }

  /**
   * Registers block color handlers for every PTS-generated slab.
   *
   * <p>For each slab stored in {@link SlabMirrorFactory#PENDING_SLABS} the method
   * registers a delegating lambda that forwards the tint query to the original
   * target block's color provider. This guarantees that grass, leaves, water, and
   * any other tintable terrain blocks keep their original appearance when converted
   * into slabs.
   *
   * @param event the Forge/NeoForge block color registration event
   */
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
      var blockColors = event.getBlockColors();
      for (PtsTerrainSlabBlock slab : SlabMirrorFactory.PENDING_SLABS.values()) {
        Block target = slab.getTargetBlock();
        if (target != Blocks.AIR) {
          event.register((state, level, pos, tintIndex) -> {

            BlockState targetState = target.defaultBlockState();
            for (Property<?> prop : state.getProperties()) {
              if (targetState.hasProperty(prop)) {
                targetState = applyPropertyUnsafe(targetState, prop, state.getValue(prop));
              }
            }

            int color = -1;
            try {
              color = blockColors.getColor(targetState, level, pos, tintIndex);
            } catch (Exception ignored) {}
            
            if (color == -1 && level != null) {
              color = blockColors.getColor(targetState, null, null, tintIndex);
            }
            
            return color;
          }, slab);
        }
      }
    }

    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
      var itemColors = event.getItemColors();
      for (PtsTerrainSlabBlock slab : SlabMirrorFactory.PENDING_SLABS.values()) {
        Block target = slab.getTargetBlock();
        if (target != Blocks.AIR) {
          event.register((stack, tintIndex) -> {
            return itemColors.getColor(new ItemStack(target), tintIndex);
          }, slab.asItem());
        }
      }
    }
  }
}